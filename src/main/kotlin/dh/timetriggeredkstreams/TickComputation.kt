package dh.timetriggeredkstreams

import dh.timetriggeredkstreams.api.CatchUpMode

data class TickPlan(
    val fires: List<Long>,
    val dueCount: Long,
    val skippedCount: Long,
    val newNextDueEpochMs: Long
)

fun computeTickPlan(
    wallClockNowEpochMs: Long,
    nextDueEpochMs: Long,
    intervalMs: Long,
    catchUpMode: CatchUpMode,
    maxCatchUp: Int
): TickPlan {
    if (wallClockNowEpochMs < nextDueEpochMs) {
        return TickPlan(
            fires = emptyList(),
            dueCount = 0,
            skippedCount = 0,
            newNextDueEpochMs = nextDueEpochMs
        )
    }

    val missed = (wallClockNowEpochMs - nextDueEpochMs) / intervalMs
    val dueCount = missed + 1
    val lastDue = nextDueEpochMs + (missed * intervalMs)

    return when (catchUpMode) {
        CatchUpMode.LATEST_ONLY -> {
            TickPlan(
                fires = listOf(lastDue),
                dueCount = dueCount,
                skippedCount = dueCount - 1,
                newNextDueEpochMs = lastDue + intervalMs
            )
        }
        CatchUpMode.CATCH_UP_ALL -> {
            val fires = (nextDueEpochMs..lastDue step intervalMs).toList()
            TickPlan(
                fires = fires,
                dueCount = dueCount,
                skippedCount = 0,
                newNextDueEpochMs = lastDue + intervalMs
            )
        }
        CatchUpMode.CATCH_UP_BOUNDED -> {
            val runCount = minOf(dueCount, maxCatchUp.toLong())
            val fires = if (runCount <= 0) {
                emptyList()
            } else {
                val start = lastDue - ((runCount - 1) * intervalMs)
                (start..lastDue step intervalMs).toList()
            }
            TickPlan(
                fires = fires,
                dueCount = dueCount,
                skippedCount = dueCount - runCount,
                newNextDueEpochMs = lastDue + intervalMs
            )
        }
    }
}
