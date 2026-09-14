package com.matiasnl.hakiosk.camera.thumbnail

/**
 * Small thread-safe LRU cache bounded by the total size of its values in bytes (not by entry count),
 * so a few large images can't blow the budget. Pure JVM (android.util.LruCache isn't unit-testable).
 */
class ByteLruCache<K : Any, V : Any>(
    val maxBytes: Long,
    private val sizeOf: (V) -> Long,
) {
    private val entries = LinkedHashMap<K, V>(16, 0.75f, true)
    private var currentBytes = 0L

    val sizeBytes: Long @Synchronized get() = currentBytes

    @Synchronized
    operator fun get(key: K): V? = entries[key]

    /** Stores [value] and evicts least recently used entries over budget (including [value] if it alone exceeds it). */
    @Synchronized
    fun put(key: K, value: V) {
        entries.put(key, value)?.let { currentBytes -= sizeOf(it) }
        currentBytes += sizeOf(value)
        val iterator = entries.entries.iterator()
        while (currentBytes > maxBytes && iterator.hasNext()) {
            val eldest = iterator.next()
            currentBytes -= sizeOf(eldest.value)
            iterator.remove()
        }
    }

    @Synchronized
    fun clear() {
        entries.clear()
        currentBytes = 0
    }
}
