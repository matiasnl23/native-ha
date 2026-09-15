package com.matiasnl.hakiosk.ui.dashboard.tiles

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraOptionsSectionTest {

    @Test
    fun `parseRefreshSecondsInput keeps only digits and blank parses as null`() {
        assertEquals(10, parseRefreshSecondsInput("10"))
        assertEquals(2, parseRefreshSecondsInput("2"))
        assertNull(parseRefreshSecondsInput(""))
        assertNull(parseRefreshSecondsInput("abc"))
        // A raw value with stray non-digits (shouldn't reach here in practice: the field filters as you type).
        assertEquals(12, parseRefreshSecondsInput("1a2"))
    }

    @Test
    fun `isRefreshTooLow flags anything below the minimum, null is not too low`() {
        assertTrue(isRefreshTooLow(0))
        assertTrue(isRefreshTooLow(1))
        assertFalse(isRefreshTooLow(2))
        assertFalse(isRefreshTooLow(10))
        assertFalse(isRefreshTooLow(null))
    }

    @Test
    fun `isManualStream is true only for a non-null value outside the known list`() {
        val known = listOf("main", "sub")

        assertFalse(isManualStream(null, known))
        assertFalse(isManualStream("main", known))
        assertTrue(isManualStream("front_door_sub", known))
        assertTrue(isManualStream("anything", emptyList()))
    }
}
