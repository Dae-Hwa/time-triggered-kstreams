package dh.timetriggeredkstreams.api

import java.time.Duration

enum class CatchUpMode { LATEST_ONLY, CATCH_UP_ALL, CATCH_UP_BOUNDED }

data class TickSchedulerConfig(
    val intervalMs: Long = 60_000,
    val alignToMinute: Boolean = true,
    val checkPeriodMs: Long = 1_000,
    val catchUpMode: CatchUpMode = CatchUpMode.LATEST_ONLY,
    val maxCatchUp: Int = 60,
    val scope: Scope = Scope.PARTITION,
    val outputTopic: String = "time-triggered-ticks",
    val storeName: String? = null,
) {
    enum class Scope { PARTITION, GLOBAL_SINGLETON }

    fun intervalDuration(): Duration = Duration.ofMillis(intervalMs)
}
