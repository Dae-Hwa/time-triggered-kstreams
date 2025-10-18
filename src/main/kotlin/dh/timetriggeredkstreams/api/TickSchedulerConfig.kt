package dh.timetriggeredkstreams.api

import java.time.Duration

data class TickSchedulerConfig(
    val intervalMs: Long = 60_000,
    val alignToMinute: Boolean = true,
    val scope: Scope = Scope.PARTITION,
    val outputTopic: String = "time-triggered-ticks",
    val storeName: String? = null,
) {
    enum class Scope { PARTITION, GLOBAL_SINGLETON }

    fun intervalDuration(): Duration = Duration.ofMillis(intervalMs)
}


