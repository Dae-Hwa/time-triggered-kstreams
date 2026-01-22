package dh.timetriggeredkstreams.processor

import dh.timetriggeredkstreams.api.StoreAccessor
import dh.timetriggeredkstreams.api.TickHandler
import dh.timetriggeredkstreams.api.TickInvocationContext
import dh.timetriggeredkstreams.api.TickSchedulerConfig
import dh.timetriggeredkstreams.computeTickPlan
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
    private val tickHandler: TickHandler<KOut, VOut>,
    private val timeProvider: () -> Long = { System.currentTimeMillis() }
) : Processor<Any, Any, KOut, VOut> {

    private lateinit var context: ProcessorContext<KOut, VOut>
    private var tickSchedule: Cancellable? = null
    private var inMemoryNextDueEpochMs: Long? = null

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

        scheduleChecks()
        resolveNextDueEpochMs()
    }

    override fun process(record: Record<Any, Any>) {
        // No-op: ticks are produced by punctuator regardless of input
    }

    override fun close() {
        tickSchedule?.cancel()
    }

    private fun scheduleChecks() {
        tickSchedule = context.schedule(
            Duration.ofMillis(schedulerConfig.checkPeriodMs),
            PunctuationType.WALL_CLOCK_TIME
        ) { timestamp ->
            handleCheck(timestamp)
        }
    }

    private fun handleCheck(wallClockNowEpochMs: Long) {
        val nextDueEpochMs = resolveNextDueEpochMs()
        val plan = computeTickPlan(
            wallClockNowEpochMs = wallClockNowEpochMs,
            nextDueEpochMs = nextDueEpochMs,
            intervalMs = schedulerConfig.intervalMs,
            catchUpMode = schedulerConfig.catchUpMode,
            maxCatchUp = schedulerConfig.maxCatchUp
        )

        if (plan.fires.isNotEmpty()) {
            val accessor = StoreAccessor(readOnlyStore = roStore, readWriteStore = rwStore)
            val taskId = runCatching { context.taskId().toString() }.getOrDefault("unknown")
            plan.fires.forEach { fireAt ->
                val invocationContext = TickInvocationContext(
                    wallClockNowEpochMs = wallClockNowEpochMs,
                    fireAtEpochMs = fireAt,
                    dueCount = plan.dueCount,
                    skippedCount = plan.skippedCount,
                    catchUpMode = schedulerConfig.catchUpMode,
                    taskId = taskId,
                    processorContext = context,
                    storeAccessor = accessor
                )
                val kv = tickHandler.onTick(invocationContext)
                if (kv != null) {
                    context.forward(Record(kv.key, kv.value, fireAt))
                }
            }
        }

        persistNextDueEpochMs(plan.newNextDueEpochMs)
    }

    private fun resolveNextDueEpochMs(): Long {
        val cached = inMemoryNextDueEpochMs
        if (cached != null) return cached

        val stored = readNextDueEpochMs()
        if (stored != null) {
            inMemoryNextDueEpochMs = stored
            return stored
        }

        val initialNextDue = initialNextDueEpochMs(timeProvider())
        persistNextDueEpochMs(initialNextDue)
        return initialNextDue
    }

    private fun readNextDueEpochMs(): Long? {
        val store = rwStore ?: roStore ?: return null
        return store.get(NEXT_DUE_KEY) as? Long
    }

    private fun persistNextDueEpochMs(nextDueEpochMs: Long) {
        rwStore?.put(NEXT_DUE_KEY, nextDueEpochMs)
        inMemoryNextDueEpochMs = nextDueEpochMs
    }

    private fun initialNextDueEpochMs(nowEpochMs: Long): Long =
        if (schedulerConfig.alignToMinute) {
            ((nowEpochMs / 60_000) + 1) * 60_000
        } else {
            nowEpochMs + schedulerConfig.intervalMs
        }

    private companion object {
        const val NEXT_DUE_KEY: String = "next_due_ms"
    }
}
