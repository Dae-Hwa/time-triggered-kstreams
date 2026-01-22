package dh.timetriggeredkstreams.api

import org.apache.kafka.streams.processor.api.ProcessorContext

class TickInvocationContext(
    val wallClockNowEpochMs: Long,
    val fireAtEpochMs: Long,
    val dueCount: Long,
    val skippedCount: Long,
    val catchUpMode: CatchUpMode,
    val taskId: String,
    val processorContext: ProcessorContext<*, *>?,
    val storeAccessor: StoreAccessor?
) {
    fun <K, V> store(): StoreAccessor.ReadWrite<K, V>? = storeAccessor?.asReadWrite()
    fun readOnly(): StoreAccessor.ReadOnly? = storeAccessor?.asReadOnly()
    fun rawContext(): ProcessorContext<*, *>? = processorContext
}
