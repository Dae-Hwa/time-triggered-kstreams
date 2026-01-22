package dh.timetriggeredkstreams

import dh.timetriggeredkstreams.api.TickHandler
import dh.timetriggeredkstreams.api.TickSchedulerConfig
import dh.timetriggeredkstreams.topology.TickTopologyBuilder
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.apache.kafka.common.serialization.Serdes
import org.apache.kafka.streams.KeyValue
import org.apache.kafka.streams.StreamsConfig
import org.apache.kafka.streams.Topology
import org.apache.kafka.streams.TopologyTestDriver
import org.apache.kafka.streams.test.TestRecord
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.util.*

class TickTopologyBuilderNoInputTest {

    private lateinit var driver: TopologyTestDriver

    @BeforeEach
    fun setup() {
        val topology = Topology()

        val fixedNow = 100_000L
        val config = TickSchedulerConfig(
            intervalMs = 5_000,
            alignToMinute = false,
            outputTopic = "ticks-no-input"
        )
        val handler = TickHandler<String, String> { ctx ->
            KeyValue("tick", ctx.fireAtEpochMs.toString())
        }

        TickTopologyBuilder.addTickProcessor(
            topology = topology,
            schedulerConfig = config,
            tickHandler = handler,
            anchorTopic = TickTopologyBuilder.DEFAULT_ANCHOR_TOPIC,
            outputTopic = "ticks-no-input",
            ensureWritableStore = true,
            storeName = null,
            timeProvider = { fixedNow }
        )

        val props = Properties().apply {
            put(StreamsConfig.APPLICATION_ID_CONFIG, "tick-builder-no-input-test")
            put(StreamsConfig.BOOTSTRAP_SERVERS_CONFIG, "dummy:9092")
            put(StreamsConfig.DEFAULT_KEY_SERDE_CLASS_CONFIG, Serdes.StringSerde::class.java)
            put(StreamsConfig.DEFAULT_VALUE_SERDE_CLASS_CONFIG, Serdes.StringSerde::class.java)
        }
        driver = TopologyTestDriver(topology, props, Instant.ofEpochMilli(fixedNow))
    }

    @AfterEach
    fun tearDown() {
        driver.close()
    }

    @Test
    fun `should emit tick without any input using wall clock`() {
        val outputTopic = driver.createOutputTopic(
            "ticks-no-input",
            Serdes.String().deserializer(),
            Serdes.String().deserializer()
        )

        // No input is provided to anchor. Advance wall clock a bit beyond one interval.
        driver.advanceWallClockTime(Duration.ofSeconds(5))

        val out1: TestRecord<String, String>? = if (!outputTopic.isEmpty) outputTopic.readRecord() else null
        out1.shouldNotBeNull()
        out1.key() shouldBe "tick"

        // Should not emit another tick until time advances again
        outputTopic.isEmpty shouldBe true

        driver.advanceWallClockTime(Duration.ofSeconds(5))
        val out2: TestRecord<String, String>? = if (!outputTopic.isEmpty) outputTopic.readRecord() else null
        out2.shouldNotBeNull()
        out2.key() shouldBe "tick"
    }
}
