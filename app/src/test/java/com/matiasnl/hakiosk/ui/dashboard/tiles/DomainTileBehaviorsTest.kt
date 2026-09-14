package com.matiasnl.hakiosk.ui.dashboard.tiles

import org.junit.Assert.assertEquals
import org.junit.Test

class DomainTileBehaviorsTest {

    private fun tapOf(domain: String) = DomainTileBehaviors.forDomain(domain).tapAction

    @Test
    fun `toggle domains keep toggling`() {
        listOf("light", "switch", "fan", "input_boolean", "automation").forEach { domain ->
            assertEquals(domain, TileAction.TOGGLE, tapOf(domain))
        }
    }

    @Test
    fun `scenes and scripts run with turn_on`() {
        assertEquals(TileAction.TURN_ON, tapOf("scene"))
        assertEquals(TileAction.TURN_ON, tapOf("script"))
    }

    @Test
    fun `cameras open the focus view`() {
        assertEquals(TileAction.OPEN_CAMERA, tapOf("camera"))
    }

    @Test
    fun `sensors and unknown domains fall back to the read-only default`() {
        listOf("sensor", "binary_sensor", "weather", "made_up").forEach { domain ->
            assertEquals(domain, DefaultTileBehavior, DomainTileBehaviors.forDomain(domain))
            assertEquals(domain, TileAction.NONE, tapOf(domain))
        }
    }
}
