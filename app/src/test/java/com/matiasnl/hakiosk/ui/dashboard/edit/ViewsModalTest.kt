package com.matiasnl.hakiosk.ui.dashboard.edit

import org.junit.Assert.assertEquals
import org.junit.Test

class ViewsModalTest {

    private val nameFor: (Int) -> String = { "Vista $it" }

    @Test
    fun `default name numbers after the existing view count`() {
        assertEquals("Vista 3", nextDefaultViewName(listOf("Principal", "Arriba"), nameFor))
    }

    @Test
    fun `default name skips numbers already taken`() {
        // Two views -> "Vista 3", which is taken -> "Vista 4".
        assertEquals("Vista 4", nextDefaultViewName(listOf("Principal", "Vista 3"), nameFor))
        assertEquals("Vista 5", nextDefaultViewName(listOf("Vista 3", "Vista 4", "Vista 2", "Vista 1"), nameFor))
    }
}
