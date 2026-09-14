package com.matiasnl.hakiosk.ui.dashboard.tiles

import com.matiasnl.hakiosk.data.dashboard.TileTapAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun `domains without a details panel ignore the tile's tap preference`() {
        listOf("switch", "scene", "camera", "sensor").forEach { domain ->
            val behavior = DomainTileBehaviors.forDomain(domain)
            assertEquals(domain, null, behavior.details)
            assertFalse(domain, behavior.offersTapActionChoice)
            TileTapAction.entries.forEach { preference ->
                assertEquals(domain, behavior.tapAction, behavior.resolveTap(preference))
            }
        }
    }

    @Test
    fun `sensors and unknown domains fall back to the read-only default`() {
        listOf("sensor", "binary_sensor", "weather", "made_up").forEach { domain ->
            assertEquals(domain, DefaultTileBehavior, DomainTileBehaviors.forDomain(domain))
            assertEquals(domain, TileAction.NONE, tapOf(domain))
        }
    }
}
