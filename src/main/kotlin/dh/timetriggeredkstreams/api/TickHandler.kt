package dh.timetriggeredkstreams.api

import org.apache.kafka.streams.KeyValue

fun interface TickHandler<K, V> {
    fun onTick(context: TickInvocationContext): KeyValue<K, V>?
}


