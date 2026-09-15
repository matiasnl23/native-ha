package com.matiasnl.hakiosk.data.ha.domain

import com.matiasnl.hakiosk.data.ha.HaEntity
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ClimateTest {
    private fun entity(state: String, attributes: String = "{}") = HaEntity(
        entityId = "climate.living",
        state = state,
        attributes = Json.parseToJsonElement(attributes) as JsonObject,
        lastChanged = "2026-09-14T00:00:00Z",
    )

    private val splitAc = entity(
        "cool",
        """{"hvac_modes":["off","cool","heat","dry","fan_only","auto","mystery"],"min_temp":16,"max_temp":30,
           "target_temp_step":1,"current_temperature":24.5,"temperature":22,"hvac_action":"cooling",
           "fan_modes":["auto","low","high"],"fan_mode":"auto","swing_modes":["off","vertical"],"swing_mode":"off",
           "preset_modes":["none","eco","boost"],"preset_mode":"eco","supported_features":441}""",
    )

    @Test
    fun parsesAFullSplitAirConditioner() {
        val caps = ClimateCapabilities.from(splitAc)

        assertEquals(HvacMode.COOL, caps.hvacMode)
        assertEquals(
            listOf(HvacMode.OFF, HvacMode.COOL, HvacMode.HEAT, HvacMode.DRY, HvacMode.FAN_ONLY, HvacMode.AUTO),
            caps.hvacModes,
        )
        assertEquals(HvacAction.COOLING, caps.hvacAction)
        assertEquals(24.5, caps.currentTemperature!!, 0.0)
        assertEquals(22.0, caps.targetTemperature!!, 0.0)
        assertEquals(16.0, caps.minTemperature, 0.0)
        assertEquals(30.0, caps.maxTemperature, 0.0)
        assertEquals(1.0, caps.temperatureStep, 0.0)
        assertTrue(caps.hasTargetTemperature)
        assertFalse(caps.hasTargetTemperatureRange)
        assertTrue(caps.hasFanModes)
        assertTrue(caps.hasPresetModes)
        assertTrue(caps.hasSwingModes)
        assertFalse(caps.hasSwingHorizontalModes)
        assertFalse(caps.hasTargetHumidity)
        assertEquals("eco", caps.presetMode)
    }

    @Test
    fun modeListsWithoutTheirFeatureBitAreNotOffered() {
        val caps = ClimateCapabilities.from(
            entity("heat", """{"hvac_modes":["off","heat"],"temperature":20,"fan_modes":["low"],"supported_features":1}"""),
        )

        assertTrue(caps.hasTargetTemperature)
        assertFalse(caps.hasFanModes)
    }

    @Test
    fun heatCoolThermostatExposesARangeAndDefaultsTheStep() {
        val caps = ClimateCapabilities.from(
            entity(
                "heat_cool",
                """{"hvac_modes":["off","heat_cool"],"target_temp_low":20,"target_temp_high":24.5,"temperature":null,
                   "current_humidity":55,"humidity":50,"min_humidity":30,"max_humidity":70,"supported_features":7}""",
            ),
        )

        assertTrue(caps.hasTargetTemperatureRange)
        assertFalse(caps.hasTargetTemperature)
        assertTrue(caps.hasTargetHumidity)
        assertEquals(ClimateCapabilities.DEFAULT_TEMPERATURE_STEP, caps.temperatureStep, 0.0)
        assertEquals(7.0, caps.minTemperature, 0.0)
        assertEquals(35.0, caps.maxTemperature, 0.0)
    }

    @Test
    fun unavailableStateHasNoModeAndMissingAttributesAreSafe() {
        val caps = ClimateCapabilities.from(entity("unavailable"))

        assertNull(caps.hvacMode)
        assertTrue(caps.hvacModes.isEmpty())
        assertNull(caps.hvacAction)
        assertNull(caps.currentTemperature)
        assertFalse(caps.hasTargetTemperature)
    }

    @Test
    fun commandsCarryHaFieldNamesAndStayInRange() {
        val caps = ClimateCapabilities.from(splitAc)

        val hvac = ClimateCommands.setHvacMode(HvacMode.FAN_ONLY)
        assertEquals("set_hvac_mode", hvac.service)
        assertEquals("fan_only", hvac.data["hvac_mode"]!!.jsonPrimitive.content)

        assertEquals(30.0, ClimateCommands.setTemperature(40.0, caps).data["temperature"]!!.jsonPrimitive.content.toDouble(), 0.0)

        val range = ClimateCommands.setTemperatureRange(low = 26.0, high = 10.0, capabilities = caps)
        assertEquals(16.0, range.data["target_temp_low"]!!.jsonPrimitive.content.toDouble(), 0.0)
        assertEquals(26.0, range.data["target_temp_high"]!!.jsonPrimitive.content.toDouble(), 0.0)

        assertEquals("boost", ClimateCommands.setPresetMode("boost").data["preset_mode"]!!.jsonPrimitive.content)
        assertEquals("high", ClimateCommands.setFanMode("high").data["fan_mode"]!!.jsonPrimitive.content)
        assertEquals("vertical", ClimateCommands.setSwingMode("vertical").data["swing_mode"]!!.jsonPrimitive.content)
        assertEquals("climate", ClimateCommands.turnOff().domain)
    }

    @Test
    fun humidityIsClampedToTheEntityRange() {
        val caps = ClimateCapabilities.from(
            entity("dry", """{"humidity":50,"min_humidity":30,"max_humidity":70,"supported_features":4}"""),
        )

        assertEquals("70", ClimateCommands.setHumidity(90, caps).data["humidity"]!!.jsonPrimitive.content)
    }
}
