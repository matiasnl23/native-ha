package com.matiasnl.hakiosk.camera.thumbnail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ByteLruCacheTest {

    private fun cache(maxBytes: Long) = ByteLruCache<String, ByteArray>(maxBytes) { it.size.toLong() }

    @Test
    fun `evicts least recently used entries once over the byte budget`() {
        val cache = cache(maxBytes = 10)
        cache.put("a", ByteArray(4))
        cache.put("b", ByteArray(4))
        cache["a"] // a is now more recent than b

        cache.put("c", ByteArray(4))

        assertNull(cache["b"])
        assertEquals(4, cache["a"]?.size)
        assertEquals(4, cache["c"]?.size)
        assertEquals(8, cache.sizeBytes)
    }

    @Test
    fun `replacing a key updates the accounted size`() {
        val cache = cache(maxBytes = 10)
        cache.put("a", ByteArray(8))
        cache.put("a", ByteArray(2))

        assertEquals(2, cache.sizeBytes)
    }

    @Test
    fun `a single value larger than the budget is not kept`() {
        val cache = cache(maxBytes = 10)
        cache.put("a", ByteArray(4))
        cache.put("huge", ByteArray(11))

        assertNull(cache["huge"])
        assertNull(cache["a"])
        assertEquals(0, cache.sizeBytes)
    }
}
