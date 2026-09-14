package com.matiasnl.hakiosk.ui.dashboard.tiles.light

import com.matiasnl.hakiosk.data.ha.domain.ServiceCall
import com.matiasnl.hakiosk.ui.dashboard.tiles.FakeEntityControlSource
import com.matiasnl.hakiosk.ui.dashboard.tiles.OPTIMISTIC_CONFIRM_TIMEOUT_MILLIS
import com.matiasnl.hakiosk.ui.dashboard.tiles.testEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private const val ID = "light.living"

private const val FULL_ATTRS =
    """{"supported_color_modes":["color_temp","hs"],"color_mode":"color_temp","brightness":153,
       "color_temp_kelvin":3000,"min_color_temp_kelvin":2200,"max_color_temp_kelvin":6500,"hs_color":[30.0,40.0]}"""

@OptIn(ExperimentalCoroutinesApi::class)
class LightPanelControllerTest {

    /** A controller whose coroutines run eagerly on the test scheduler, in a scope the test can cancel. */
    private fun TestScope.controller(source: FakeEntityControlSource): Pair<LightPanelController, Job> {
        val job = Job(backgroundScope.coroutineContext[Job])
        val scope = CoroutineScope(job + UnconfinedTestDispatcher(testScheduler))
        return LightPanelController(ID, source, scope) to job
    }

    private fun data(json: String) = Json.parseToJsonElement(json) as JsonObject

    @Test
    fun `an on-off-only light shows just the switch`() = runTest {
        val source = FakeEntityControlSource(testEntity(ID, "on", """{"supported_color_modes":["onoff"]}"""))
        val (controller, _) = controller(source)

        assertTrue(controller.isOn)
        assertTrue(controller.isAvailable)
        assertFalse(controller.showBrightness)
        assertFalse(controller.showColorTemp)
        assertFalse(controller.showColor)
    }

    @Test
    fun `a brightness-only light shows brightness and nothing else`() = runTest {
        val source = FakeEntityControlSource(
            testEntity(ID, "on", """{"supported_color_modes":["brightness"],"brightness":153}"""),
        )
        val (controller, _) = controller(source)

        assertTrue(controller.showBrightness)
        assertFalse(controller.showColorTemp)
        assertFalse(controller.showColor)
        assertEquals(60f, controller.brightnessPercent)
    }

    @Test
    fun `a color_temp plus hs light shows every control with its own Kelvin range`() = runTest {
        val source = FakeEntityControlSource(testEntity(ID, "on", FULL_ATTRS))
        val (controller, _) = controller(source)

        assertTrue(controller.showBrightness)
        assertTrue(controller.showColorTemp)
        assertTrue(controller.showColor)
        assertEquals(2200, controller.minColorTempKelvin)
        assertEquals(6500, controller.maxColorTempKelvin)
        assertEquals(3000f, controller.colorTempKelvin)
        assertEquals(30f, controller.hueDegrees)
        assertEquals(40f, controller.saturationPercent)
    }

    @Test
    fun `unavailable or missing lights show no controls`() = runTest {
        val source = FakeEntityControlSource(testEntity(ID, "unavailable", FULL_ATTRS))
        val (controller, _) = controller(source)
        assertFalse(controller.isAvailable)
        assertFalse(controller.showBrightness || controller.showColorTemp || controller.showColor)

        val (missing, _) = controller(FakeEntityControlSource())
        assertTrue(missing.isMissing)
        assertFalse(missing.showBrightness)
    }

    @Test
    fun `dragging sends nothing and release sends exactly one command with the final value`() = runTest {
        val source = FakeEntityControlSource(testEntity(ID, "on", FULL_ATTRS))
        val (controller, _) = controller(source)

        controller.onBrightnessChange(10f)
        controller.onBrightnessChange(25f)
        controller.onBrightnessChange(30.4f)
        assertTrue(source.calls.isEmpty())
        assertEquals(30.4f, controller.brightnessPercent)

        controller.onBrightnessChangeFinished()
        controller.onBrightnessChangeFinished() // A second finish without a drag sends nothing.

        assertEquals(listOf(ID to ServiceCall("light", "turn_on", data("""{"brightness_pct":30}"""))), source.calls)
    }

    @Test
    fun `the local value stays until the entity confirms it`() = runTest {
        val source = FakeEntityControlSource(testEntity(ID, "on", FULL_ATTRS))
        val (controller, _) = controller(source)

        controller.onBrightnessChange(30f)
        controller.onBrightnessChangeFinished()
        // An unrelated update with the old brightness doesn't end the hold.
        source.update(testEntity(ID, "on", FULL_ATTRS.replace("\"hs_color\":[30.0,40.0]", "\"hs_color\":[31.0,40.0]")))
        assertEquals(30f, controller.brightnessPercent)

        // 77/255 = 30%: confirmed, so the panel follows the entity again.
        source.update(testEntity(ID, "on", FULL_ATTRS.replace("\"brightness\":153", "\"brightness\":77")))
        source.update(testEntity(ID, "on", FULL_ATTRS.replace("\"brightness\":153", "\"brightness\":80")))
        assertEquals(31f, controller.brightnessPercent)
    }

    @Test
    fun `without confirmation the local value is dropped after the timeout`() = runTest {
        val source = FakeEntityControlSource(testEntity(ID, "on", FULL_ATTRS))
        val (controller, _) = controller(source)

        controller.onBrightnessChange(30f)
        advanceTimeBy(OPTIMISTIC_CONFIRM_TIMEOUT_MILLIS * 2) // Holding while dragging never times out.
        controller.onBrightnessChangeFinished()

        advanceTimeBy(OPTIMISTIC_CONFIRM_TIMEOUT_MILLIS - 1)
        assertEquals(30f, controller.brightnessPercent)

        advanceTimeBy(2)
        assertEquals(60f, controller.brightnessPercent)
    }

    @Test
    fun `releasing brightness at zero turns the light off and the switch follows immediately`() = runTest {
        val source = FakeEntityControlSource(testEntity(ID, "on", FULL_ATTRS))
        val (controller, _) = controller(source)

        controller.onBrightnessChange(0f)
        controller.onBrightnessChangeFinished()

        assertEquals(ServiceCall("light", "turn_off"), source.calls.single().second)
        assertFalse(controller.isOn)
    }

    @Test
    fun `color temperature is clamped to the light's range`() = runTest {
        val source = FakeEntityControlSource(testEntity(ID, "on", FULL_ATTRS))
        val (controller, _) = controller(source)

        controller.onColorTempChange(9000f)
        assertEquals(6500f, controller.colorTempKelvin)
        controller.onColorTempChangeFinished()
        controller.onColorTempChange(1000f)
        controller.onColorTempChangeFinished()

        assertEquals(
            listOf(
                ServiceCall("light", "turn_on", data("""{"color_temp_kelvin":6500}""")),
                ServiceCall("light", "turn_on", data("""{"color_temp_kelvin":2200}""")),
            ),
            source.calls.map { it.second },
        )
    }

    @Test
    fun `changing the color of an off light sends one turn_on with hs_color and shows it on`() = runTest {
        val source = FakeEntityControlSource(
            testEntity(ID, "off", """{"supported_color_modes":["hs"],"min_color_temp_kelvin":null}"""),
        )
        val (controller, _) = controller(source)
        assertFalse(controller.isOn)

        controller.onHueChange(120.4f)
        controller.onHueChangeFinished()
        controller.onSaturationChange(55f)
        controller.onSaturationChangeFinished()

        assertEquals(
            listOf(
                ServiceCall("light", "turn_on", data("""{"hs_color":[120.0,100.0]}""")),
                ServiceCall("light", "turn_on", data("""{"hs_color":[120.0,55.0]}""")),
            ),
            source.calls.map { it.second },
        )
        assertTrue(controller.isOn)
    }

    @Test
    fun `the switch sends turn_on and turn_off`() = runTest {
        val source = FakeEntityControlSource(testEntity(ID, "off", """{"supported_color_modes":["onoff"]}"""))
        val (controller, _) = controller(source)

        controller.setOn(true)
        assertTrue(controller.isOn)
        controller.setOn(false)

        assertEquals(listOf(ServiceCall("light", "turn_on"), ServiceCall("light", "turn_off")), source.calls.map { it.second })
    }

    @Test
    fun `a failed call surfaces HA's message and drops the local value`() = runTest {
        val source = FakeEntityControlSource(testEntity(ID, "on", FULL_ATTRS))
        source.result = Result.failure(IllegalStateException("Light is not reachable"))
        val (controller, _) = controller(source)

        controller.onBrightnessChange(10f)
        controller.onBrightnessChangeFinished()

        assertEquals("Light is not reachable", controller.error)
        assertEquals(60f, controller.brightnessPercent)

        source.result = Result.success(Unit)
        controller.onBrightnessChange(20f)
        controller.onBrightnessChangeFinished()
        assertNull(controller.error)
    }

    @Test
    fun `the entity is only collected while the panel's scope is alive`() = runTest {
        val source = FakeEntityControlSource(testEntity(ID, "on", FULL_ATTRS))
        val (controller, job) = controller(source)
        assertEquals(1, source.activeSubscriptions)

        job.cancel()

        assertEquals(0, source.activeSubscriptions)
        source.update(testEntity(ID, "off", FULL_ATTRS))
        assertTrue(controller.isOn)
    }
}
