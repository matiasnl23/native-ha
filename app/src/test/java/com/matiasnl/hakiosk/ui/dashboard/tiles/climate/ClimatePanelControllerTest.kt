package com.matiasnl.hakiosk.ui.dashboard.tiles.climate

import com.matiasnl.hakiosk.data.ha.domain.HvacMode
import com.matiasnl.hakiosk.ui.dashboard.tiles.FakeEntityControlSource
import com.matiasnl.hakiosk.ui.dashboard.tiles.testEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private const val ID = "climate.living"

private const val SPLIT_ATTRS =
    """{"hvac_modes":["off","cool","heat","dry","fan_only","auto"],"min_temp":16,"max_temp":30,"target_temp_step":1,
       "current_temperature":24.5,"temperature":22,"hvac_action":"cooling",
       "fan_modes":["auto","low","high"],"fan_mode":"auto","swing_modes":["off","vertical"],"swing_mode":"off",
       "preset_modes":["none","eco"],"preset_mode":"none","supported_features":441}"""

private const val RANGE_ATTRS =
    """{"hvac_modes":["off","heat_cool"],"min_temp":7,"max_temp":35,"current_temperature":21,
       "target_temp_low":20,"target_temp_high":21,"humidity":45,"min_humidity":30,"max_humidity":70,"supported_features":6}"""

@OptIn(ExperimentalCoroutinesApi::class)
class ClimatePanelControllerTest {

    /** A controller whose coroutines run eagerly on the test scheduler, in a scope the test can cancel. */
    private fun TestScope.controller(source: FakeEntityControlSource): Pair<ClimatePanelController, Job> {
        val job = Job(backgroundScope.coroutineContext[Job])
        val scope = CoroutineScope(job + UnconfinedTestDispatcher(testScheduler))
        return ClimatePanelController(ID, source, scope) to job
    }

    private fun FakeEntityControlSource.number(index: Int, key: String): Double =
        calls[index].second.data[key]!!.jsonPrimitive.content.toDouble()

    @Test
    fun `a split air conditioner shows a single setpoint and every mode it supports`() = runTest {
        val source = FakeEntityControlSource(testEntity(ID, "cool", SPLIT_ATTRS))
        val (controller, _) = controller(source)

        assertTrue(controller.showTargetTemperature)
        assertFalse(controller.showTargetRange)
        assertFalse(controller.showTargetHumidity)
        assertEquals(22.0, controller.targetTemperature!!, 0.0)
        assertEquals(HvacMode.COOL, controller.hvacMode)
        assertEquals(6, controller.hvacModes.size)
        assertEquals(listOf("auto", "low", "high"), controller.fanModes)
        assertEquals(listOf("off", "vertical"), controller.swingModes)
        assertEquals(listOf("none", "eco"), controller.presetModes)
        assertTrue(controller.swingHorizontalModes.isEmpty())
    }

    @Test
    fun `repeated setpoint taps show at once and send one call after the delay`() = runTest {
        val source = FakeEntityControlSource(testEntity(ID, "cool", SPLIT_ATTRS))
        val (controller, _) = controller(source)

        repeat(3) { controller.stepTargetTemperature(1) }

        assertEquals(25.0, controller.targetTemperature!!, 0.0)
        assertTrue(source.calls.isEmpty())

        advanceTimeBy(SETPOINT_COMMIT_DELAY_MILLIS + 1)

        assertEquals(1, source.calls.size)
        assertEquals("set_temperature", source.calls.single().second.service)
        assertEquals(25.0, source.number(0, "temperature"), 0.0)
        assertEquals(25.0, controller.targetTemperature!!, 0.0)
    }

    @Test
    fun `closing the panel sends a pending setpoint even as its scope is cancelled`() = runTest {
        val source = FakeEntityControlSource(testEntity(ID, "cool", SPLIT_ATTRS))
        val (controller, job) = controller(source)

        controller.stepTargetTemperature(-1)
        controller.close()
        job.cancel()

        assertEquals(1, source.calls.size)
        assertEquals(21.0, source.number(0, "temperature"), 0.0)
    }

    @Test
    fun `the setpoint stops at the entity's limits and sends nothing past them`() = runTest {
        val source = FakeEntityControlSource(testEntity(ID, "cool", SPLIT_ATTRS.replace("\"temperature\":22", "\"temperature\":30")))
        val (controller, _) = controller(source)

        assertFalse(controller.canStepTargetTemperature(1))
        assertTrue(controller.canStepTargetTemperature(-1))
        controller.stepTargetTemperature(1)
        advanceTimeBy(SETPOINT_COMMIT_DELAY_MILLIS + 1)

        assertTrue(source.calls.isEmpty())
    }

    @Test
    fun `a range keeps low at or below high and sends both ends`() = runTest {
        val source = FakeEntityControlSource(testEntity(ID, "heat_cool", RANGE_ATTRS))
        val (controller, _) = controller(source)

        assertTrue(controller.showTargetRange)
        assertFalse(controller.showTargetTemperature)
        // Low 20, high 21, default step 0.5: two steps up would pass high, so it stops at 21.
        controller.stepTargetLow(1)
        controller.stepTargetLow(1)
        controller.stepTargetLow(1)
        assertFalse(controller.canStepTargetLow(1))
        controller.stepTargetHigh(1)
        advanceTimeBy(SETPOINT_COMMIT_DELAY_MILLIS + 1)

        assertEquals(1, source.calls.size)
        assertEquals(21.0, source.number(0, "target_temp_low"), 0.0)
        assertEquals(21.5, source.number(0, "target_temp_high"), 0.0)
    }

    @Test
    fun `a mode tap shows at once, sends set_hvac_mode and reverts when the call fails`() = runTest {
        val source = FakeEntityControlSource(testEntity(ID, "cool", SPLIT_ATTRS))
        val (controller, _) = controller(source)

        controller.setHvacMode(HvacMode.HEAT)
        assertEquals(HvacMode.HEAT, controller.hvacMode)
        assertEquals("set_hvac_mode", source.calls.single().second.service)
        assertEquals("heat", source.calls.single().second.data["hvac_mode"]!!.jsonPrimitive.content)

        source.result = Result.failure(IllegalStateException("boom"))
        controller.setHvacMode(HvacMode.DRY)

        assertEquals(HvacMode.COOL, controller.hvacMode)
        assertEquals("boom", controller.error)
    }

    @Test
    fun `a mode change sends a pending setpoint first`() = runTest {
        val source = FakeEntityControlSource(testEntity(ID, "cool", SPLIT_ATTRS))
        val (controller, _) = controller(source)

        controller.stepTargetTemperature(1)
        controller.setHvacMode(HvacMode.HEAT)

        assertEquals(listOf("set_temperature", "set_hvac_mode"), source.calls.map { it.second.service })
    }

    @Test
    fun `fan, swing and preset taps send their own service and ignore unsupported values`() = runTest {
        val source = FakeEntityControlSource(testEntity(ID, "cool", SPLIT_ATTRS))
        val (controller, _) = controller(source)

        controller.setFanMode("high")
        controller.setSwingMode("vertical")
        controller.setPresetMode("eco")
        controller.setPresetMode("boost")
        controller.setFanMode("high")

        assertEquals(listOf("set_fan_mode", "set_swing_mode", "set_preset_mode"), source.calls.map { it.second.service })
        assertEquals("high", controller.fanMode)
        assertEquals("eco", controller.presetMode)
    }

    @Test
    fun `humidity sends one call on release`() = runTest {
        val source = FakeEntityControlSource(testEntity(ID, "heat_cool", RANGE_ATTRS))
        val (controller, _) = controller(source)

        assertTrue(controller.showTargetHumidity)
        controller.onHumidityChange(52f)
        controller.onHumidityChange(55.4f)
        controller.onHumidityChangeFinished()

        assertEquals(1, source.calls.size)
        assertEquals("set_humidity", source.calls.single().second.service)
        assertEquals("55", source.calls.single().second.data["humidity"]!!.jsonPrimitive.content)
    }

    @Test
    fun `an unavailable device offers no controls and sends nothing`() = runTest {
        val source = FakeEntityControlSource(testEntity(ID, "unavailable", SPLIT_ATTRS))
        val (controller, _) = controller(source)

        assertFalse(controller.isAvailable)
        assertNull(controller.hvacMode)
        assertTrue(controller.hvacModes.isEmpty())
        assertTrue(controller.fanModes.isEmpty())
        assertFalse(controller.showTargetTemperature)
        controller.stepTargetTemperature(1)
        controller.setHvacMode(HvacMode.COOL)
        advanceTimeBy(SETPOINT_COMMIT_DELAY_MILLIS + 1)

        assertTrue(source.calls.isEmpty())
    }
}
