package dh.timetriggeredkstreams.config

import dh.timetriggeredkstreams.api.TickSchedulerConfig
import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "ttk")
data class TtkProperties(
    val intervalMs: Long = 60_000,
    val alignToMinute: Boolean = true,
    val scope: TickSchedulerConfig.Scope = TickSchedulerConfig.Scope.PARTITION,
    val outputTopic: String = "time-triggered-ticks",
    val storeName: String? = null,
)


