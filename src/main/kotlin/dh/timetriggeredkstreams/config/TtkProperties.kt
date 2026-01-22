package dh.timetriggeredkstreams.config

import dh.timetriggeredkstreams.api.CatchUpMode
import dh.timetriggeredkstreams.api.TickSchedulerConfig
import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "ttk")
data class TtkProperties(
    val intervalMs: Long = 60_000,
    val alignToMinute: Boolean = true,
    val checkPeriodMs: Long = 1_000,
    val catchUpMode: CatchUpMode = CatchUpMode.LATEST_ONLY,
    val maxCatchUp: Int = 60,
    val scope: TickSchedulerConfig.Scope = TickSchedulerConfig.Scope.PARTITION,
    val outputTopic: String = "time-triggered-ticks",
    val storeName: String? = null,
)
