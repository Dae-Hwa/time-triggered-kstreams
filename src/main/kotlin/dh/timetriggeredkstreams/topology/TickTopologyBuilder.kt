package dh.timetriggeredkstreams.topology

import dh.timetriggeredkstreams.api.TickHandler
import dh.timetriggeredkstreams.api.TickSchedulerConfig
import dh.timetriggeredkstreams.processor.TickProcessor
import org.apache.kafka.common.serialization.Serdes
import org.apache.kafka.streams.Topology
import org.apache.kafka.streams.processor.api.ProcessorSupplier
import org.apache.kafka.streams.state.Stores

object TickTopologyBuilder {

    const val DEFAULT_ANCHOR_TOPIC: String = "ttk-anchor"
    const val DEFAULT_OUTPUT_TOPIC: String = "time-triggered-ticks"
    const val DEFAULT_STORE_NAME: String = "ttk-checkpoint-store"
    const val DEFAULT_SOURCE_NAME: String = "ttk-anchor-source"
    const val DEFAULT_PROCESSOR_NAME: String = "ttk-tick-processor"
    const val DEFAULT_SINK_NAME: String = "ttk-sink"

    fun <KOut, VOut> addTickProcessor(
        topology: Topology,
        schedulerConfig: TickSchedulerConfig,
        tickHandler: TickHandler<KOut, VOut>,
        anchorTopic: String = DEFAULT_ANCHOR_TOPIC,
        outputTopic: String? = null,
        ensureWritableStore: Boolean = true,
        storeName: String? = null,
        sourceName: String = DEFAULT_SOURCE_NAME,
        processorName: String = DEFAULT_PROCESSOR_NAME,
        sinkName: String = DEFAULT_SINK_NAME
    ) {
        // Source for task creation (anchor)
        topology.addSource(sourceName, anchorTopic)

        val finalStoreName = if (ensureWritableStore) {
            storeName ?: schedulerConfig.storeName ?: DEFAULT_STORE_NAME
        } else schedulerConfig.storeName

        val effectiveConfig = if (finalStoreName != null && finalStoreName != schedulerConfig.storeName) {
            schedulerConfig.copy(storeName = finalStoreName)
        } else schedulerConfig

        // Processor with effective config
        topology.addProcessor(
            processorName,
            ProcessorSupplier { TickProcessor(effectiveConfig, tickHandler) },
            sourceName
        )

        // Auto-create writable in-memory state store if requested
        if (ensureWritableStore && finalStoreName != null) 
        {
            val supplier = Stores.inMemoryKeyValueStore(finalStoreName)
            val storeBuilder = Stores.keyValueStoreBuilder(supplier, Serdes.String(), Serdes.Long())
            topology.addStateStore(storeBuilder, processorName)
        }

        // Sink to output topic
        val outTopic = outputTopic ?: schedulerConfig.outputTopic.ifEmpty { DEFAULT_OUTPUT_TOPIC }
        topology.addSink(sinkName, outTopic, processorName)
    }
}
