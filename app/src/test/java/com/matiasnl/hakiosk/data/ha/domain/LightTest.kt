package com.matiasnl.hakiosk.data.ha.domain

import com.matiasnl.hakiosk.data.ha.HaEntity
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LightTest {
    private fun entity(state: String, attributes: String = "{}") = HaEntity(
        entityId = "light.kitchen",
        state = state,
        attributes = Json.parseToJsonElement(attributes) as JsonObject,
        lastChanged = "2026-09-14T00:00:00Z",
    )

    private fun obj(json: String) = Json.parseToJsonElement(json) as JsonObject

    @Test
    fun onoffOnlyLightHasNoBrightnessOrColorSupport() {
        val caps = LightCapabilities.from(
            entity("on", """{"supported_color_modes":["onoff"],"color_mode":"onoff"}"""),
        )
        assertEquals(setOf(LightColorMode.ONOFF), caps.supportedColorModes)
        assertEquals(LightColorMode.ONOFF, caps.colorMode)
        assertTrue(caps.isOn)
        assertFalse(caps.supportsBrightness)
        assertFalse(caps.supportsColorTemp)
        assertFalse(caps.supportsColor)
        assertNull(caps.brightnessPercent)
        assertNull(caps.colorTempKelvin)
        assertNull(caps.minColorTempKelvin)
        assertNull(caps.maxColorTempKelvin)
        assertNull(caps.hue)
        assertNull(caps.saturation)
    }

    @Test
    fun brightnessOnlyLightConvertsZeroToTwoFiftyFiveScaleToPercent() {
        val caps = LightCapabilities.from(
            entity("on", """{"supported_color_modes":["brightness"],"color_mode":"brightness","brightness":128}"""),
        )
        assertTrue(caps.supportsBrightness)
        assertFalse(caps.supportsColorTemp)
        assertFalse(caps.supportsColor)
        // 128 / 255 * 100 = 50.196... -> rounds to 50.
        assertEquals(50, caps.brightnessPercent)

        assertEquals(0, LightCapabilities.from(entity("on", """{"supported_color_modes":["brightness"],"brightness":0}""")).brightnessPercent)
        assertEquals(
            100,
            LightCapabilities.from(entity("on", """{"supported_color_modes":["brightness"],"brightness":255}""")).brightnessPercent,
        )
    }

    @Test
    fun colorTempAndHsLightExposesRangesAndCurrentColor() {
        val caps = LightCapabilities.from(
            entity(
                "on",
                """{
                    "supported_color_modes":["color_temp","hs"],
                    "color_mode":"hs",
                    "brightness":255,
                    "color_temp_kelvin":4000,
                    "min_color_temp_kelvin":2000,
                    "max_color_temp_kelvin":6500,
                    "hs_color":[210.5,80.0]
                }""",
            ),
        )
        assertEquals(setOf(LightColorMode.COLOR_TEMP, LightColorMode.HS), caps.supportedColorModes)
        assertEquals(LightColorMode.HS, caps.colorMode)
        assertTrue(caps.supportsBrightness)
        assertTrue(caps.supportsColorTemp)
        assertTrue(caps.supportsColor)
        assertEquals(100, caps.brightnessPercent)
        assertEquals(4000, caps.colorTempKelvin)
        assertEquals(2000, caps.minColorTempKelvin)
        assertEquals(6500, caps.maxColorTempKelvin)
        assertEquals(210.5, caps.hue!!, 0.001)
        assertEquals(80.0, caps.saturation!!, 0.001)
    }

    @Test
    fun colorTempRangeFallsBackToHaDefaultsWhenMissing() {
        val caps = LightCapabilities.from(
            entity("on", """{"supported_color_modes":["color_temp"],"color_mode":"color_temp"}"""),
        )
        assertEquals(2000, caps.minColorTempKelvin)
        assertEquals(6535, caps.maxColorTempKelvin)
    }

    @Test
    fun offLightOmitsBrightnessAndColorAttributes() {
        val caps = LightCapabilities.from(
            entity("off", """{"supported_color_modes":["color_temp","hs"],"color_mode":null}"""),
        )
        assertFalse(caps.isOn)
        assertNull(caps.colorMode)
        assertNull(caps.brightnessPercent)
        assertNull(caps.colorTempKelvin)
        assertNull(caps.hue)
        assertNull(caps.saturation)
        // Range attributes are capability-level, not state-level: still reported even while off.
        assertEquals(2000, caps.minColorTempKelvin)
        assertEquals(6535, caps.maxColorTempKelvin)
    }

    @Test
    fun malformedAttributesDoNotCrashAndDegradeToUnknown() {
        val caps = LightCapabilities.from(
            entity(
                "on",
                """{
                    "supported_color_modes":["hs","bogus_mode","unknown",3],
                    "color_mode":"bogus_mode",
                    "brightness":"not-a-number",
                    "color_temp_kelvin":"nope",
                    "hs_color":[210.5]
                }""",
            ),
        )
        assertEquals(setOf(LightColorMode.HS), caps.supportedColorModes)
        assertNull(caps.colorMode)
        assertNull(caps.brightnessPercent)
        assertNull(caps.colorTempKelvin)
        assertNull(caps.hue)
        assertNull(caps.saturation)

        // Missing supported_color_modes entirely.
        val bare = LightCapabilities.from(entity("on"))
        assertEquals(emptySet<LightColorMode>(), bare.supportedColorModes)
        assertFalse(bare.supportsBrightness)
    }

    @Test
    fun brightnessAndHsColorAreClampedToValidRanges() {
        val caps = LightCapabilities.from(
            entity(
                "on",
                """{"supported_color_modes":["hs"],"brightness":9001,"hs_color":[720.0,-40.0]}""",
            ),
        )
        assertEquals(100, caps.brightnessPercent)
        assertEquals(360.0, caps.hue!!, 0.001)
        assertEquals(0.0, caps.saturation!!, 0.001)

        val negativeBrightness = LightCapabilities.from(entity("on", """{"supported_color_modes":["hs"],"brightness":-10}"""))
        assertEquals(0, negativeBrightness.brightnessPercent)
    }

    @Test
    fun onOffTurnOffAndToggleHaveNoData() {
        assertEquals(ServiceCall("light", "turn_on", obj("{}")), LightCommands.turnOn())
        assertEquals(ServiceCall("light", "turn_off", obj("{}")), LightCommands.turnOff())
        assertEquals(ServiceCall("light", "toggle", obj("{}")), LightCommands.toggle())
    }

    @Test
    fun setBrightnessPercentTurnsOffAtZeroAndClampsAtHundred() {
        assertEquals(LightCommands.turnOff(), LightCommands.setBrightnessPercent(0))
        assertEquals(LightCommands.turnOff(), LightCommands.setBrightnessPercent(-5))
        assertEquals(
            ServiceCall("light", "turn_on", obj("""{"brightness_pct":40}""")),
            LightCommands.setBrightnessPercent(40),
        )
        assertEquals(
            ServiceCall("light", "turn_on", obj("""{"brightness_pct":100}""")),
            LightCommands.setBrightnessPercent(150),
        )
    }

    @Test
    fun setColorTempKelvinClampsToCapabilitiesRange() {
        val caps = LightCapabilities.from(
            entity(
                "on",
                """{"supported_color_modes":["color_temp"],"min_color_temp_kelvin":2000,"max_color_temp_kelvin":6500}""",
            ),
        )
        assertEquals(
            ServiceCall("light", "turn_on", obj("""{"color_temp_kelvin":4000}""")),
            LightCommands.setColorTempKelvin(4000, caps),
        )
        assertEquals(
            ServiceCall("light", "turn_on", obj("""{"color_temp_kelvin":2000}""")),
            LightCommands.setColorTempKelvin(1000, caps),
        )
        assertEquals(
            ServiceCall("light", "turn_on", obj("""{"color_temp_kelvin":6500}""")),
            LightCommands.setColorTempKelvin(9000, caps),
        )
    }

    @Test
    fun setColorTempKelvinPassesThroughWhenNoRangeIsKnown() {
        val caps = LightCapabilities.from(entity("on", """{"supported_color_modes":["onoff"]}"""))
        assertEquals(
            ServiceCall("light", "turn_on", obj("""{"color_temp_kelvin":4000}""")),
            LightCommands.setColorTempKelvin(4000, caps),
        )
    }

    @Test
    fun setHsColorClampsHueAndSaturation() {
        assertEquals(
            ServiceCall("light", "turn_on", obj("""{"hs_color":[210.5,80.0]}""")),
            LightCommands.setHsColor(210.5, 80.0),
        )
        assertEquals(
            ServiceCall("light", "turn_on", obj("""{"hs_color":[360.0,100.0]}""")),
            LightCommands.setHsColor(720.0, 250.0),
        )
        assertEquals(
            ServiceCall("light", "turn_on", obj("""{"hs_color":[0.0,0.0]}""")),
            LightCommands.setHsColor(-10.0, -5.0),
        )
    }
}
