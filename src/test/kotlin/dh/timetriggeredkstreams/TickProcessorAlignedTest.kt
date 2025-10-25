package dh.timetriggeredkstreams

import dh.timetriggeredkstreams.api.TickHandler
import dh.timetriggeredkstreams.api.TickSchedulerConfig
import dh.timetriggeredkstreams.processor.TickProcessor
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.apache.kafka.common.serialization.Serdes
import org.apache.kafka.streams.KeyValue
import org.apache.kafka.streams.StreamsConfig
import org.apache.kafka.streams.Topology
import org.apache.kafka.streams.TopologyTestDriver
import org.apache.kafka.streams.processor.api.ProcessorSupplier
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.Properties

class TickProcessorAlignedTest {

    private lateinit var driver: TopologyTestDriver

    @BeforeEach
    fun setup() {
        val topology = Topology()
        topology.addSource("source", "anchor")

        val config = TickSchedulerConfig(
            intervalMs = 60_000,
            alignToMinute = true,
            outputTopic = "ticks"
        )
        // Fix time at exactly HH:mm:30.000, so the delay should be 30_000ms to the next minute
        val fixedNow = 90_000L // arbitrary epoch for test simplicity (00:01:30.000)
        val handler = TickHandler<String, String> { ctx ->
            KeyValue("tick", ctx.nowEpochMs.toString())
        }

        topology.addProcessor(
            "tick-processor",
            ProcessorSupplier { TickProcessor(config, handler) { fixedNow } },
            "source"
        )
        topology.addSink("sink", "ticks", "tick-processor")

        val props = Properties().apply {
            put(StreamsConfig.APPLICATION_ID_CONFIG, "tick-processor-aligned-test")
            put(StreamsConfig.BOOTSTRAP_SERVERS_CONFIG, "dummy:9092")
            put(StreamsConfig.DEFAULT_KEY_SERDE_CLASS_CONFIG, Serdes.StringSerde::class.java)
            put(StreamsConfig.DEFAULT_VALUE_SERDE_CLASS_CONFIG, Serdes.StringSerde::class.java)
        }
        driver = TopologyTestDriver(topology, props)

        // activate topology
        val inputTopic = driver.createInputTopic(
            "anchor",
            Serdes.String().serializer(),
            Serdes.String().serializer()
        )
        inputTopic.pipeInput("k", "v")
    }

    @AfterEach
    fun tearDown() {
        driver.close()
    }

    @Test
    fun `first tick should occur at next minute boundary`() {
        val output = driver.createOutputTopic(
            "ticks",
            Serdes.String().deserializer(),
            Serdes.String().deserializer()
        )

        // Before next minute boundary (29 seconds) should be empty
        driver.advanceWallClockTime(Duration.ofSeconds(29))
        output.isEmpty shouldBe true

        // Cross the boundary by +2 seconds → expect 1 tick
        driver.advanceWallClockTime(Duration.ofSeconds(2))
        val first = if (!output.isEmpty) output.readRecord() else null
        first.shouldNotBeNull()
        first.key() shouldBe "tick"

        // After aligned start, the periodic schedule is 60_000ms; 59 s shouldn't produce another
        driver.advanceWallClockTime(Duration.ofSeconds(59))
        output.isEmpty shouldBe true

        // +1s should produce the second tick
        driver.advanceWallClockTime(Duration.ofSeconds(1))
        val second = if (!output.isEmpty) output.readRecord() else null
        second.shouldNotBeNull()
        second.key() shouldBe "tick"
    }
}
