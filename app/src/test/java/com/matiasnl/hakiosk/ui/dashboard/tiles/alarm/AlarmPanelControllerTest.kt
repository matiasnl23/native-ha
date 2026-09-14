package com.matiasnl.hakiosk.ui.dashboard.tiles.alarm

import com.matiasnl.hakiosk.data.ha.domain.AlarmArmMode
import com.matiasnl.hakiosk.data.ha.domain.AlarmCodeFormat
import com.matiasnl.hakiosk.data.ha.domain.ServiceCall
import com.matiasnl.hakiosk.ui.dashboard.tiles.FakeEntityControlSource
import com.matiasnl.hakiosk.ui.dashboard.tiles.testEntity
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private const val ID = "alarm_control_panel.home"

/** ARM_HOME(1) + ARM_AWAY(2) + ARM_VACATION(32). */
private const val HOME_AWAY_VACATION = 35

@OptIn(ExperimentalCoroutinesApi::class)
class AlarmPanelControllerTest {

    private fun TestScope.controller(source: FakeEntityControlSource): AlarmPanelController {
        val scope = CoroutineScope(Job(backgroundScope.coroutineContext[Job]) + UnconfinedTestDispatcher(testScheduler))
        return AlarmPanelController(ID, source, scope)
    }

    private fun TestScope.controllerFor(state: String, attributes: String) =
        controller(FakeEntityControlSource(testEntity(ID, state, attributes)))

    private fun data(json: String) = Json.parseToJsonElement(json) as JsonObject

    @Test
    fun `disarmed panels offer exactly the supported arm modes, in a fixed order`() = runTest {
        val controller = controllerFor("disarmed", """{"supported_features":$HOME_AWAY_VACATION}""")

        assertEquals(listOf(AlarmArmMode.HOME, AlarmArmMode.AWAY, AlarmArmMode.VACATION), controller.armModes)
        assertFalse(controller.canDisarm)

        val all = controllerFor("disarmed", """{"supported_features":63}""")
        assertEquals(AlarmArmMode.entries, all.armModes)
    }

    @Test
    fun `armed, pending, arming and triggered panels offer only Desarmar`() = runTest {
        listOf("armed_home", "armed_away", "armed_night", "armed_vacation", "armed_custom_bypass", "pending", "arming", "triggered")
            .forEach { state ->
                val controller = controllerFor(state, """{"supported_features":$HOME_AWAY_VACATION}""")
                assertTrue(state, controller.canDisarm)
                assertTrue(state, controller.armModes.isEmpty())
            }
    }

    @Test
    fun `disarming, unavailable and unknown panels offer nothing`() = runTest {
        listOf("disarming", "unavailable", "unknown").forEach { state ->
            val controller = controllerFor(state, """{"supported_features":$HOME_AWAY_VACATION,"code_format":"number"}""")
            assertFalse(state, controller.canDisarm)
            assertTrue(state, controller.armModes.isEmpty())
            assertEquals(state, AlarmCodeFormat.NONE, controller.codeInput)
        }
    }

    @Test
    fun `the arm modes follow the entity when it changes state`() = runTest {
        val source = FakeEntityControlSource(testEntity(ID, "armed_away", """{"supported_features":3}"""))
        val controller = controller(source)
        assertTrue(controller.armModes.isEmpty())

        source.update(testEntity(ID, "disarmed", """{"supported_features":3}"""))

        assertEquals(listOf(AlarmArmMode.HOME, AlarmArmMode.AWAY), controller.armModes)
        assertFalse(controller.canDisarm)
    }

    @Test
    fun `no code format means no code entry for arming or disarming`() = runTest {
        assertEquals(AlarmCodeFormat.NONE, controllerFor("disarmed", """{"supported_features":3}""").codeInput)
        assertEquals(AlarmCodeFormat.NONE, controllerFor("armed_home", """{"supported_features":3}""").codeInput)
    }

    @Test
    fun `a number code is asked for arming unless code_arm_required is false, and always for disarming`() = runTest {
        val armDefault = controllerFor("disarmed", """{"supported_features":3,"code_format":"number"}""")
        assertTrue(armDefault.armCodeRequired)
        assertEquals(AlarmCodeFormat.NUMBER, armDefault.codeInput)

        val armWithoutCode = controllerFor("disarmed", """{"supported_features":3,"code_format":"number","code_arm_required":false}""")
        assertFalse(armWithoutCode.armCodeRequired)
        assertEquals(AlarmCodeFormat.NONE, armWithoutCode.codeInput)

        val disarm = controllerFor("armed_away", """{"supported_features":3,"code_format":"number","code_arm_required":false}""")
        assertTrue(disarm.disarmCodeRequired)
        assertEquals(AlarmCodeFormat.NUMBER, disarm.codeInput)
    }

    @Test
    fun `a text code shows the text entry`() = runTest {
        assertEquals(AlarmCodeFormat.TEXT, controllerFor("triggered", """{"supported_features":3,"code_format":"text"}""").codeInput)
        assertEquals(AlarmCodeFormat.TEXT, controllerFor("disarmed", """{"supported_features":3,"code_format":"text"}""").codeInput)
    }

    @Test
    fun `keypad digits append, delete removes the last one and clear empties the code`() = runTest {
        val controller = controllerFor("disarmed", """{"supported_features":3,"code_format":"number"}""")

        "12a3".forEach(controller::onDigit)
        assertEquals("123", controller.code)
        controller.onDeleteDigit()
        assertEquals("12", controller.code)
        controller.onClearCode()
        assertEquals("", controller.code)
        controller.onDeleteDigit()
        assertEquals("", controller.code)

        repeat(ALARM_CODE_MAX_LENGTH + 5) { controller.onDigit('9') }
        assertEquals(ALARM_CODE_MAX_LENGTH, controller.code.length)
    }

    @Test
    fun `arming with a required code sends it and clears it right away`() = runTest {
        val source = FakeEntityControlSource(testEntity(ID, "disarmed", """{"supported_features":3,"code_format":"number"}"""))
        val controller = controller(source)

        "1234".forEach(controller::onDigit)
        controller.arm(AlarmArmMode.AWAY)

        assertEquals(listOf(ID to ServiceCall("alarm_control_panel", "alarm_arm_away", data("""{"code":"1234"}"""))), source.calls)
        assertEquals("", controller.code)
    }

    @Test
    fun `no code key is sent when no code is needed or the code is blank`() = runTest {
        val noCode = FakeEntityControlSource(testEntity(ID, "disarmed", """{"supported_features":3}"""))
        controller(noCode).arm(AlarmArmMode.HOME)
        assertEquals(ServiceCall("alarm_control_panel", "alarm_arm_home", data("{}")), noCode.calls.single().second)

        val blank = FakeEntityControlSource(testEntity(ID, "armed_home", """{"supported_features":3,"code_format":"text"}"""))
        val controller = controller(blank)
        controller.onCodeChange("   ")
        controller.disarm()
        assertEquals(ServiceCall("alarm_control_panel", "alarm_disarm", data("{}")), blank.calls.single().second)
    }

    @Test
    fun `a text code is sent on disarm`() = runTest {
        val source = FakeEntityControlSource(testEntity(ID, "triggered", """{"supported_features":3,"code_format":"text"}"""))
        val controller = controller(source)

        controller.onCodeChange("s3cret")
        controller.disarm()

        assertEquals(ServiceCall("alarm_control_panel", "alarm_disarm", data("""{"code":"s3cret"}""")), source.calls.single().second)
    }

    @Test
    fun `unsupported arm modes and disarm from a disarmed panel send nothing`() = runTest {
        val source = FakeEntityControlSource(testEntity(ID, "disarmed", """{"supported_features":1}"""))
        val controller = controller(source)

        controller.arm(AlarmArmMode.NIGHT)
        controller.disarm()

        assertTrue(source.calls.isEmpty())
    }

    @Test
    fun `busy while the call runs, ignores other actions, and finishes on success`() = runTest {
        val source = FakeEntityControlSource(testEntity(ID, "disarmed", """{"supported_features":3}"""))
        val gate = CompletableDeferred<Unit>()
        source.gate = gate
        val controller = controller(source)

        controller.arm(AlarmArmMode.HOME)
        assertTrue(controller.isBusy)
        controller.arm(AlarmArmMode.AWAY)
        assertEquals(1, source.calls.size)
        assertFalse(controller.isFinished)

        gate.complete(Unit)

        assertFalse(controller.isBusy)
        assertTrue(controller.isFinished)
        assertNull(controller.error)
    }

    @Test
    fun `a failed call surfaces HA's message, keeps the panel open and leaves no code behind`() = runTest {
        val source = FakeEntityControlSource(testEntity(ID, "armed_away", """{"supported_features":3,"code_format":"number"}"""))
        source.result = Result.failure(IllegalArgumentException("Invalid alarm code provided"))
        val controller = controller(source)

        "0000".forEach(controller::onDigit)
        controller.disarm()

        assertEquals("Invalid alarm code provided", controller.error)
        assertFalse(controller.isFinished)
        assertFalse(controller.isBusy)
        assertEquals("", controller.code)
    }

    @Test
    fun `closing forgets the code and the error`() = runTest {
        val source = FakeEntityControlSource(testEntity(ID, "armed_away", """{"supported_features":3,"code_format":"number"}"""))
        source.result = Result.failure(IllegalArgumentException("Invalid alarm code provided"))
        val controller = controller(source)
        controller.disarm()
        "98".forEach(controller::onDigit)

        controller.close()

        assertEquals("", controller.code)
        assertNull(controller.error)
    }
}
