package dh.timetriggeredkstreams

import dh.timetriggeredkstreams.api.CatchUpMode
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class TickComputationTest {

    private val intervalMs = 10_000L
    private val nextDueEpochMs = 100_000L
    private val lateNowEpochMs = nextDueEpochMs + (intervalMs * 2) + (intervalMs / 2)
    private val lastDueEpochMs = nextDueEpochMs + (intervalMs * 2)

    @Test
    fun `now before next due returns empty plan`() {
        val plan = computeTickPlan(
            wallClockNowEpochMs = nextDueEpochMs - 1,
            nextDueEpochMs = nextDueEpochMs,
            intervalMs = intervalMs,
            catchUpMode = CatchUpMode.LATEST_ONLY,
            maxCatchUp = 60
        )

        plan.fires shouldBe emptyList()
        plan.dueCount shouldBe 0
        plan.skippedCount shouldBe 0
        plan.newNextDueEpochMs shouldBe nextDueEpochMs
    }

    @Test
    fun `now equals next due triggers single fire`() {
        val plan = computeTickPlan(
            wallClockNowEpochMs = nextDueEpochMs,
            nextDueEpochMs = nextDueEpochMs,
            intervalMs = intervalMs,
            catchUpMode = CatchUpMode.LATEST_ONLY,
            maxCatchUp = 60
        )

        plan.fires shouldBe listOf(nextDueEpochMs)
        plan.dueCount shouldBe 1
        plan.skippedCount shouldBe 0
        plan.newNextDueEpochMs shouldBe nextDueEpochMs + intervalMs
    }

    @Test
    fun `late now with latest-only runs once`() {
        val plan = computeTickPlan(
            wallClockNowEpochMs = lateNowEpochMs,
            nextDueEpochMs = nextDueEpochMs,
            intervalMs = intervalMs,
            catchUpMode = CatchUpMode.LATEST_ONLY,
            maxCatchUp = 60
        )

        plan.fires shouldBe listOf(lastDueEpochMs)
        plan.dueCount shouldBe 3
        plan.skippedCount shouldBe 2
        plan.newNextDueEpochMs shouldBe lastDueEpochMs + intervalMs
    }

    @Test
    fun `late now with catch-up-all runs every due`() {
        val plan = computeTickPlan(
            wallClockNowEpochMs = lateNowEpochMs,
            nextDueEpochMs = nextDueEpochMs,
            intervalMs = intervalMs,
            catchUpMode = CatchUpMode.CATCH_UP_ALL,
            maxCatchUp = 60
        )

        plan.fires shouldBe listOf(
            nextDueEpochMs,
            nextDueEpochMs + intervalMs,
            lastDueEpochMs
        )
        plan.dueCount shouldBe 3
        plan.skippedCount shouldBe 0
        plan.newNextDueEpochMs shouldBe lastDueEpochMs + intervalMs
    }

    @Test
    fun `late now with catch-up-bounded limits runs`() {
        val plan = computeTickPlan(
            wallClockNowEpochMs = lateNowEpochMs,
            nextDueEpochMs = nextDueEpochMs,
            intervalMs = intervalMs,
            catchUpMode = CatchUpMode.CATCH_UP_BOUNDED,
            maxCatchUp = 2
        )

        plan.fires shouldBe listOf(
            nextDueEpochMs + intervalMs,
            lastDueEpochMs
        )
        plan.dueCount shouldBe 3
        plan.skippedCount shouldBe 1
        plan.newNextDueEpochMs shouldBe lastDueEpochMs + intervalMs
    }

    @Test
    fun `catch-up-bounded with max 1 only fires latest`() {
        val plan = computeTickPlan(
            wallClockNowEpochMs = lateNowEpochMs,
            nextDueEpochMs = nextDueEpochMs,
            intervalMs = intervalMs,
            catchUpMode = CatchUpMode.CATCH_UP_BOUNDED,
            maxCatchUp = 1
        )

        plan.fires shouldBe listOf(lastDueEpochMs)
        plan.dueCount shouldBe 3
        plan.skippedCount shouldBe 2
        plan.newNextDueEpochMs shouldBe lastDueEpochMs + intervalMs
    }
}
