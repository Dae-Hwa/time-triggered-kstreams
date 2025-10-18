package dh.timetriggeredkstreams.api

import org.apache.kafka.streams.processor.api.ProcessorContext

class TickInvocationContext(
    val nowEpochMs: Long,
    val taskId: String,
    private val processorContext: ProcessorContext<*, *>?,
    private val storeAccessor: StoreAccessor?
) {
    fun <K, V> store(): StoreAccessor.ReadWrite<K, V>? = storeAccessor?.asReadWrite()
    fun readOnly(): StoreAccessor.ReadOnly? = storeAccessor?.asReadOnly()
    fun rawContext(): ProcessorContext<*, *>? = processorContext
}


