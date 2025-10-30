package dh.timetriggeredkstreams.transformer

import dh.timetriggeredkstreams.api.StoreAccessor
import dh.timetriggeredkstreams.api.TickHandler
import dh.timetriggeredkstreams.api.TickInvocationContext
import dh.timetriggeredkstreams.api.TickSchedulerConfig
import java.time.Duration
import org.apache.kafka.streams.KeyValue
import org.apache.kafka.streams.kstream.Transformer
import org.apache.kafka.streams.kstream.TransformerSupplier
import org.apache.kafka.streams.processor.ProcessorContext
import org.apache.kafka.streams.processor.PunctuationType
import org.apache.kafka.streams.state.KeyValueStore
import org.apache.kafka.streams.state.ReadOnlyKeyValueStore

class TickTransformer<KOut, VOut>(
        private val schedulerConfig: TickSchedulerConfig,
        private val tickHandler: TickHandler<KOut, VOut>,
        private val timeProvider: () -> Long = { System.currentTimeMillis() }
) : Transformer<Any, Any, KeyValue<KOut, VOut>?> {

    private lateinit var context: ProcessorContext
    private var rwStore: KeyValueStore<Any, Any>? = null
    private var roStore: ReadOnlyKeyValueStore<Any, Any>? = null

    override fun init(context: ProcessorContext) {
        this.context = context

        schedulerConfig.storeName?.let { name ->
            runCatching { context.getStateStore(name) as KeyValueStore<Any, Any> }
                    .onSuccess { rwStore = it }
                    .onFailure {
                        roStore =
                                runCatching {
                                            context.getStateStore(name) as
                                                    ReadOnlyKeyValueStore<Any, Any>
                                        }
                                        .getOrNull()
                    }
        }

        if (schedulerConfig.scope == TickSchedulerConfig.Scope.GLOBAL_SINGLETON) {
            val isLeaderTask = runCatching { context.taskId().partition() == 0 }.getOrDefault(false)
            if (!isLeaderTask) return
        }

        if (schedulerConfig.alignToMinute) {
            scheduleAlignedToMinute()
        } else {
            schedulePeriodic()
        }
    }

    override fun transform(key: Any, value: Any): KeyValue<KOut, VOut>? {
        // No-op for input records; ticks are produced by punctuator
        return null
    }

    override fun close() {}

    private fun scheduleAlignedToMinute() {
        val now = timeProvider()
        val delayMs = millisToNextMinute(now)
        context.schedule(Duration.ofMillis(delayMs), PunctuationType.WALL_CLOCK_TIME) { ts ->
            handleTick(ts)
            context.schedule(schedulerConfig.intervalDuration(), PunctuationType.WALL_CLOCK_TIME) {
                    ts2 ->
                handleTick(ts2)
            }
        }
    }

    private fun schedulePeriodic() {
        context.schedule(schedulerConfig.intervalDuration(), PunctuationType.WALL_CLOCK_TIME) { ts
            ->
            handleTick(ts)
        }
    }

    private fun handleTick(timestampMs: Long) {
        val accessor = StoreAccessor(readOnlyStore = roStore, readWriteStore = rwStore)
        val invocationContext =
                TickInvocationContext(
                        nowEpochMs = timestampMs,
                        taskId =
                                runCatching { context.taskId().toString() }.getOrDefault("unknown"),
                        processorContext = null,
                        storeAccessor = accessor
                )
        val kv = tickHandler.onTick(invocationContext)
        if (kv != null) {
            // In Transformer API we don't set explicit timestamp; punctuation time is used as
            // context timestamp
            context.forward(kv.key, kv.value)
        }
    }

    private fun millisToNextMinute(nowMs: Long): Long {
        val nextMinute = ((nowMs / 60_000) + 1) * 60_000
        return nextMinute - nowMs
    }
}

fun <KOut, VOut> tickTransformerSupplier(
        schedulerConfig: TickSchedulerConfig,
        tickHandler: TickHandler<KOut, VOut>,
        timeProvider: () -> Long = { System.currentTimeMillis() }
): TransformerSupplier<Any, Any, KeyValue<KOut, VOut>?> = TransformerSupplier {
    TickTransformer(schedulerConfig, tickHandler, timeProvider)
}
