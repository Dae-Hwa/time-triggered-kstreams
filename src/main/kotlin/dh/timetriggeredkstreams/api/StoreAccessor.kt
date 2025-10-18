package dh.timetriggeredkstreams.api

import org.apache.kafka.streams.state.KeyValueStore
import org.apache.kafka.streams.state.ReadOnlyKeyValueStore

class StoreAccessor(
    private val readOnlyStore: ReadOnlyKeyValueStore<Any, Any>? = null,
    private val readWriteStore: KeyValueStore<Any, Any>? = null
) {
    interface ReadOnly {
        fun <K, V> get(key: K): V?
    }

    interface ReadWrite<K, V> : ReadOnly {
        fun put(key: K, value: V)
        fun delete(key: K)
    }

    @Suppress("UNCHECKED_CAST")
    fun asReadOnly(): ReadOnly? {
        val store = readOnlyStore ?: readWriteStore ?: return null
        return object : ReadOnly {
            override fun <K, V> get(key: K): V? = (store as ReadOnlyKeyValueStore<K, V>).get(key)
        }
    }

    @Suppress("UNCHECKED_CAST")
    fun <K, V> asReadWrite(): ReadWrite<K, V>? {
        val store = readWriteStore ?: return null
        return object : ReadWrite<K, V> {
            override fun put(key: K, value: V) {
                (store as KeyValueStore<K, V>).put(key, value)
            }

            override fun delete(key: K) {
                (store as KeyValueStore<K, V>).delete(key)
            }

            override fun <K2, V2> get(key: K2): V2? = (store as ReadOnlyKeyValueStore<K2, V2>).get(key)
        }
    }
}


