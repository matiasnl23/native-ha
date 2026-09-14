package com.matiasnl.hakiosk.ui.dashboard.tiles

import com.matiasnl.hakiosk.data.dashboard.TileTapAction
import com.matiasnl.hakiosk.data.ha.domain.AlarmPanelState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
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
    fun `lights toggle by default, open controls when the tile asks, and have a details panel`() {
        val light = DomainTileBehaviors.forDomain("light")

        assertNotNull(light.details)
        assertTrue(light.offersTapActionChoice)
        assertEquals(TileAction.TOGGLE, light.resolveTap(TileTapAction.DEFAULT))
        assertEquals(TileAction.TOGGLE, light.resolveTap(TileTapAction.TOGGLE))
        assertEquals(TileAction.OPEN_DETAILS, light.resolveTap(TileTapAction.OPEN_DETAILS))
    }

    @Test
    fun `light summary shows brightness only when supported and on`() {
        val light = DomainTileBehaviors.forDomain("light")

        assertEquals(
            TileSummary.Light(isOn = true, brightnessPercent = 60),
            light.summarize(testEntity("light.a", "on", """{"supported_color_modes":["brightness"],"brightness":153}""")),
        )
        assertEquals(
            TileSummary.Light(isOn = true, brightnessPercent = 1),
            light.summarize(testEntity("light.a", "on", """{"supported_color_modes":["hs"],"brightness":1}""")),
        )
        assertEquals(
            TileSummary.Light(isOn = true, brightnessPercent = null),
            light.summarize(testEntity("light.a", "on", """{"supported_color_modes":["onoff"],"brightness":153}""")),
        )
        assertEquals(
            TileSummary.Light(isOn = false, brightnessPercent = null),
            light.summarize(testEntity("light.a", "off", """{"supported_color_modes":["brightness"]}""")),
        )
    }

    @Test
    fun `alarm panels always open their panel on tap and never offer a tap choice`() {
        val alarm = DomainTileBehaviors.forDomain("alarm_control_panel")

        assertNotNull(alarm.details)
        assertFalse(alarm.offersTapActionChoice)
        TileTapAction.entries.forEach { preference ->
            assertEquals(preference.name, TileAction.OPEN_DETAILS, alarm.resolveTap(preference))
        }
        assertEquals(
            TileSummary.Alarm(AlarmPanelState.TRIGGERED),
            alarm.summarize(testEntity("alarm_control_panel.home", "triggered", """{"supported_features":3}""")),
        )
    }

    @Test
    fun `only domains with a tap choice contribute an edit-modal section`() {
        assertEquals(1, DomainTileBehaviors.forDomain("light").editSections(TileTapAction.DEFAULT) {}.size)
        listOf("alarm_control_panel", "switch", "scene", "camera", "sensor").forEach { domain ->
            assertTrue(domain, DomainTileBehaviors.forDomain(domain).editSections(TileTapAction.DEFAULT) {}.isEmpty())
        }
    }

    @Test
    fun `other domains keep the default summary`() {
        assertEquals(TileSummary.Default, DomainTileBehaviors.forDomain("switch").summarize(testEntity("switch.a", "on")))
        assertEquals(TileSummary.Default, DomainTileBehaviors.forDomain("sensor").summarize(testEntity("sensor.a", "21")))
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
