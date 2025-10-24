package dh.timetriggeredkstreams

import dh.timetriggeredkstreams.api.TickHandler
import dh.timetriggeredkstreams.api.TickSchedulerConfig
import dh.timetriggeredkstreams.processor.TickProcessor
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.apache.kafka.common.serialization.Serdes
import org.apache.kafka.streams.KeyValue
import org.apache.kafka.streams.Topology
import org.apache.kafka.streams.TopologyTestDriver
import org.apache.kafka.streams.StreamsConfig
import org.apache.kafka.streams.processor.api.ProcessorSupplier
import org.apache.kafka.streams.test.TestRecord
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.*

class TickProcessorTopologyTest {

    private lateinit var driver: TopologyTestDriver

    @BeforeEach
    fun setup() {
        val topology = Topology()
        topology.addSource("source", "anchor")

        val config = TickSchedulerConfig(
            intervalMs = 5_000,
            alignToMinute = false,
            outputTopic = "ticks"
        )
        val handler = TickHandler<String, String> { ctx ->
            KeyValue("tick", ctx.nowEpochMs.toString())
        }

        topology.addProcessor(
            "tick-processor",
            ProcessorSupplier { TickProcessor(config, handler) },
            "source"
        )
        topology.addSink("sink", "ticks", "tick-processor")

        val props = Properties().apply {
            put(StreamsConfig.APPLICATION_ID_CONFIG, "tick-processor-test")
            put(StreamsConfig.BOOTSTRAP_SERVERS_CONFIG, "dummy:9092")
            put(StreamsConfig.DEFAULT_KEY_SERDE_CLASS_CONFIG, org.apache.kafka.common.serialization.Serdes.StringSerde::class.java)
            put(StreamsConfig.DEFAULT_VALUE_SERDE_CLASS_CONFIG, org.apache.kafka.common.serialization.Serdes.StringSerde::class.java)
        }
        driver = TopologyTestDriver(topology, props)
    }

    @AfterEach
    fun tearDown() {
        driver.close()
    }

    @Test
    fun `should emit ticks periodically on wall clock time`() {
        // No input required; ensure the topology is active
        val inputTopic = driver.createInputTopic(
            "anchor",
            Serdes.String().serializer(),
            Serdes.String().serializer()
        )
        inputTopic.pipeInput("k", "v")

        // Advance 5 seconds, expect one tick in output topic
        driver.advanceWallClockTime(Duration.ofSeconds(5))
        val outputTopic = driver.createOutputTopic(
            "ticks",
            Serdes.String().deserializer(),
            Serdes.String().deserializer()
        )
        val out1: TestRecord<String, String>? = if (!outputTopic.isEmpty) outputTopic.readRecord() else null
        out1.shouldNotBeNull()
        out1.key() shouldBe "tick"

        // No further output without advancing time
        (outputTopic.isEmpty) shouldBe true

        // Advance another interval and expect another tick
        driver.advanceWallClockTime(Duration.ofSeconds(5))
        val out2: TestRecord<String, String>? = if (!outputTopic.isEmpty) outputTopic.readRecord() else null
        out2.shouldNotBeNull()
        out2.key() shouldBe "tick"
    }
}


