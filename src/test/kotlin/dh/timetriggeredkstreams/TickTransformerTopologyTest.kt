package dh.timetriggeredkstreams

import dh.timetriggeredkstreams.api.TickHandler
import dh.timetriggeredkstreams.api.TickSchedulerConfig
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.apache.kafka.common.serialization.Serdes
import org.apache.kafka.streams.StreamsConfig
import org.apache.kafka.streams.StreamsBuilder
import org.apache.kafka.streams.TopologyTestDriver
import org.apache.kafka.streams.processor.api.ProcessorSupplier
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.util.*

class TickTransformerTopologyTest {

    private lateinit var driver: TopologyTestDriver

    @BeforeEach
    fun setup() {
        val builder = StreamsBuilder()
        val anchor = builder.stream<String, String>("anchor")

        val fixedNow = 100_000L
        val config = TickSchedulerConfig(
            intervalMs = 5_000,
            alignToMinute = false,
            outputTopic = "ticks-tf"
        )
        val handler = TickHandler<String, String> { ctx ->
            org.apache.kafka.streams.KeyValue("tick", ctx.fireAtEpochMs.toString())
        }

        val ticks = anchor.process(
            ProcessorSupplier { dh.timetriggeredkstreams.processor.TickProcessor(config, handler) { fixedNow } }
        )
        ticks.to("ticks-tf")

        val props = Properties().apply {
            put(StreamsConfig.APPLICATION_ID_CONFIG, "tick-transformer-test")
            put(StreamsConfig.BOOTSTRAP_SERVERS_CONFIG, "dummy:9092")
            put(StreamsConfig.DEFAULT_KEY_SERDE_CLASS_CONFIG, Serdes.StringSerde::class.java)
            put(StreamsConfig.DEFAULT_VALUE_SERDE_CLASS_CONFIG, Serdes.StringSerde::class.java)
        }
        val topology = builder.build(props)
        driver = TopologyTestDriver(topology, props, Instant.ofEpochMilli(fixedNow))
    }

    @AfterEach
    fun tearDown() {
        driver.close()
    }

    @Test
    fun `should emit ticks periodically without input`() {
        val outputTopic = driver.createOutputTopic(
            "ticks-tf",
            Serdes.String().deserializer(),
            Serdes.String().deserializer()
        )

        driver.advanceWallClockTime(Duration.ofSeconds(5))
        val out1 = if (!outputTopic.isEmpty) outputTopic.readRecord() else null
        out1.shouldNotBeNull()
        out1.key() shouldBe "tick"

        outputTopic.isEmpty shouldBe true

        driver.advanceWallClockTime(Duration.ofSeconds(5))
        val out2 = if (!outputTopic.isEmpty) outputTopic.readRecord() else null
        out2.shouldNotBeNull()
        out2.key() shouldBe "tick"
    }
}
