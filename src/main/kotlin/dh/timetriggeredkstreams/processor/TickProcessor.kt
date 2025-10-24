package dh.timetriggeredkstreams.processor

import dh.timetriggeredkstreams.api.StoreAccessor
import dh.timetriggeredkstreams.api.TickHandler
import dh.timetriggeredkstreams.api.TickInvocationContext
import dh.timetriggeredkstreams.api.TickSchedulerConfig
import org.apache.kafka.streams.processor.Cancellable
import org.apache.kafka.streams.processor.PunctuationType
import org.apache.kafka.streams.processor.api.Processor
import org.apache.kafka.streams.processor.api.ProcessorContext
import org.apache.kafka.streams.processor.api.Record
import org.apache.kafka.streams.state.KeyValueStore
import org.apache.kafka.streams.state.ReadOnlyKeyValueStore
import java.time.Duration

class TickProcessor<KOut, VOut>(
    private val schedulerConfig: TickSchedulerConfig,
    private val tickHandler: TickHandler<KOut, VOut>
) : Processor<Any, Any, KOut, VOut> {

    private lateinit var context: ProcessorContext<KOut, VOut>
    private var initialSchedule: Cancellable? = null
    private var periodicSchedule: Cancellable? = null

    private var rwStore: KeyValueStore<Any, Any>? = null
    private var roStore: ReadOnlyKeyValueStore<Any, Any>? = null

    override fun init(context: ProcessorContext<KOut, VOut>) {
        this.context = context

        // Optional store binding
        schedulerConfig.storeName?.let { name ->
            // Try RW first, fallback to RO if available
            runCatching { context.getStateStore(name) as KeyValueStore<Any, Any> }
                .onSuccess { rwStore = it }
                .onFailure {
                    roStore = runCatching { context.getStateStore(name) as ReadOnlyKeyValueStore<Any, Any> }.getOrNull()
                }
        }

        // Scope gate for GLOBAL_SINGLETON: only partition 0 schedules
        if (schedulerConfig.scope == TickSchedulerConfig.Scope.GLOBAL_SINGLETON) {
            val isLeaderTask = runCatching { context.taskId().partition() == 0 }.getOrDefault(false)
            if (!isLeaderTask) return
        }

        if (schedulerConfig.alignToMinute) {
            val now = System.currentTimeMillis()
            val delayMs = millisToNextMinute(now)
            initialSchedule = context.schedule(Duration.ofMillis(delayMs), PunctuationType.WALL_CLOCK_TIME) { timestamp ->
                handleTick(timestamp)
                // Switch to periodic schedule aligned to minute
                periodicSchedule = context.schedule(schedulerConfig.intervalDuration(), PunctuationType.WALL_CLOCK_TIME) { ts ->
                    handleTick(ts)
                }
                initialSchedule?.cancel()
                initialSchedule = null
            }
        } else {
            periodicSchedule = context.schedule(schedulerConfig.intervalDuration(), PunctuationType.WALL_CLOCK_TIME) { timestamp ->
                handleTick(timestamp)
            }
        }
    }

    override fun process(record: Record<Any, Any>) {
        // No-op: ticks are produced by punctuator regardless of input
    }

    override fun close() {
        initialSchedule?.cancel()
        periodicSchedule?.cancel()
    }

    private fun handleTick(timestampMs: Long) {
        val accessor = StoreAccessor(
            readOnlyStore = roStore,
            readWriteStore = rwStore
        )
        val invocationContext = TickInvocationContext(
            nowEpochMs = timestampMs,
            taskId = runCatching { context.taskId().toString() }.getOrDefault("unknown"),
            processorContext = context,
            storeAccessor = accessor
        )
        val kv = tickHandler.onTick(invocationContext)
        if (kv != null) {
            context.forward(Record(kv.key, kv.value, timestampMs))
        }
    }

    private fun millisToNextMinute(nowMs: Long): Long {
        val nextMinute = ((nowMs / 60_000) + 1) * 60_000
        return nextMinute - nowMs
    }
}



