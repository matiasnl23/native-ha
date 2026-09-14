package com.matiasnl.hakiosk.data.ha.domain

import com.matiasnl.hakiosk.data.ha.HaEntity
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AlarmPanelTest {
    private fun entity(state: String, attributes: String = "{}") = HaEntity(
        entityId = "alarm_control_panel.home",
        state = state,
        attributes = Json.parseToJsonElement(attributes) as JsonObject,
        lastChanged = "2026-09-14T00:00:00Z",
    )

    @Test
    fun stateMapsEveryKnownValueAndFallsBackForUnknownOnes() {
        assertEquals(AlarmPanelState.DISARMED, AlarmPanelState.from("disarmed"))
        assertEquals(AlarmPanelState.ARMED_HOME, AlarmPanelState.from("armed_home"))
        assertEquals(AlarmPanelState.ARMED_AWAY, AlarmPanelState.from("armed_away"))
        assertEquals(AlarmPanelState.ARMED_NIGHT, AlarmPanelState.from("armed_night"))
        assertEquals(AlarmPanelState.ARMED_VACATION, AlarmPanelState.from("armed_vacation"))
        assertEquals(AlarmPanelState.ARMED_CUSTOM_BYPASS, AlarmPanelState.from("armed_custom_bypass"))
        assertEquals(AlarmPanelState.PENDING, AlarmPanelState.from("pending"))
        assertEquals(AlarmPanelState.ARMING, AlarmPanelState.from("arming"))
        assertEquals(AlarmPanelState.DISARMING, AlarmPanelState.from("disarming"))
        assertEquals(AlarmPanelState.TRIGGERED, AlarmPanelState.from("triggered"))
        assertEquals(AlarmPanelState.UNAVAILABLE, AlarmPanelState.from("unavailable"))
        assertEquals(AlarmPanelState.UNKNOWN, AlarmPanelState.from("unknown"))
        assertEquals(AlarmPanelState.UNKNOWN, AlarmPanelState.from("some_future_state"))
        assertEquals(AlarmPanelState.ARMED_AWAY, AlarmPanelState.from(entity("armed_away")))
    }

    @Test
    fun capabilitiesDecodeSupportedFeaturesBits() {
        // ARM_HOME(1) + ARM_AWAY(2) + ARM_NIGHT(4) + ARM_CUSTOM_BYPASS(16) + ARM_VACATION(32) = 55, no TRIGGER(8).
        val caps = AlarmPanelCapabilities.from(entity("disarmed", """{"supported_features":55}"""))
        assertEquals(
            setOf(AlarmArmMode.HOME, AlarmArmMode.AWAY, AlarmArmMode.NIGHT, AlarmArmMode.VACATION, AlarmArmMode.CUSTOM_BYPASS),
            caps.supportedArmModes,
        )
        assertFalse(caps.supportsTrigger)

        val homeAndTriggerOnly = AlarmPanelCapabilities.from(entity("disarmed", """{"supported_features":9}"""))
        assertEquals(setOf(AlarmArmMode.HOME), homeAndTriggerOnly.supportedArmModes)
        assertTrue(homeAndTriggerOnly.supportsTrigger)

        val noFeatures = AlarmPanelCapabilities.from(entity("disarmed"))
        assertEquals(emptySet<AlarmArmMode>(), noFeatures.supportedArmModes)
        assertFalse(noFeatures.supportsTrigger)
    }

    @Test
    fun codeFormatMapsKnownValuesAndDefaultsToNone() {
        assertEquals(AlarmCodeFormat.NUMBER, AlarmPanelCapabilities.from(entity("disarmed", """{"code_format":"number"}""")).codeFormat)
        assertEquals(AlarmCodeFormat.TEXT, AlarmPanelCapabilities.from(entity("disarmed", """{"code_format":"text"}""")).codeFormat)
        assertEquals(AlarmCodeFormat.NONE, AlarmPanelCapabilities.from(entity("disarmed", """{"code_format":null}""")).codeFormat)
        assertEquals(AlarmCodeFormat.NONE, AlarmPanelCapabilities.from(entity("disarmed")).codeFormat)
        // Malformed value: don't crash, degrade to NONE.
        assertEquals(AlarmCodeFormat.NONE, AlarmPanelCapabilities.from(entity("disarmed", """{"code_format":7}""")).codeFormat)
    }

    @Test
    fun codeArmRequiredDefaultsToTrueWhenAbsent() {
        assertTrue(AlarmPanelCapabilities.from(entity("disarmed")).codeArmRequired)
        assertFalse(AlarmPanelCapabilities.from(entity("disarmed", """{"code_arm_required":false}""")).codeArmRequired)
        assertTrue(AlarmPanelCapabilities.from(entity("disarmed", """{"code_arm_required":true}""")).codeArmRequired)
    }

    @Test
    fun disarmCodeIsRequiredExactlyWhenACodeFormatIsDeclared() {
        assertTrue(AlarmPanelCapabilities.from(entity("disarmed", """{"code_format":"number"}""")).codeRequiredForDisarm)
        assertFalse(AlarmPanelCapabilities.from(entity("disarmed")).codeRequiredForDisarm)
    }

    private fun obj(json: String) = Json.parseToJsonElement(json) as JsonObject

    @Test
    fun armCommandsOmitCodeWhenBlankAndIncludeItOtherwise() {
        assertEquals(ServiceCall("alarm_control_panel", "alarm_arm_home", obj("{}")), AlarmPanelCommands.armHome())
        assertEquals(ServiceCall("alarm_control_panel", "alarm_arm_home", obj("{}")), AlarmPanelCommands.armHome(""))
        assertEquals(ServiceCall("alarm_control_panel", "alarm_arm_home", obj("{}")), AlarmPanelCommands.armHome("   "))
        assertEquals(ServiceCall("alarm_control_panel", "alarm_arm_home", obj("""{"code":"1234"}""")), AlarmPanelCommands.armHome("1234"))

        assertEquals(ServiceCall("alarm_control_panel", "alarm_arm_away", obj("""{"code":"1234"}""")), AlarmPanelCommands.armAway("1234"))
        assertEquals(ServiceCall("alarm_control_panel", "alarm_arm_night", obj("""{"code":"1234"}""")), AlarmPanelCommands.armNight("1234"))
        assertEquals(ServiceCall("alarm_control_panel", "alarm_arm_vacation", obj("""{"code":"1234"}""")), AlarmPanelCommands.armVacation("1234"))
        assertEquals(
            ServiceCall("alarm_control_panel", "alarm_arm_custom_bypass", obj("""{"code":"1234"}""")),
            AlarmPanelCommands.armCustomBypass("1234"),
        )
        assertEquals(ServiceCall("alarm_control_panel", "alarm_disarm", obj("""{"code":"1234"}""")), AlarmPanelCommands.disarm("1234"))
        assertEquals(ServiceCall("alarm_control_panel", "alarm_disarm", obj("{}")), AlarmPanelCommands.disarm())
    }

    @Test
    fun armDispatchesToTheMatchingPerModeCommand() {
        assertEquals(AlarmPanelCommands.armHome("1234"), AlarmPanelCommands.arm(AlarmArmMode.HOME, "1234"))
        assertEquals(AlarmPanelCommands.armAway("1234"), AlarmPanelCommands.arm(AlarmArmMode.AWAY, "1234"))
        assertEquals(AlarmPanelCommands.armNight("1234"), AlarmPanelCommands.arm(AlarmArmMode.NIGHT, "1234"))
        assertEquals(AlarmPanelCommands.armVacation("1234"), AlarmPanelCommands.arm(AlarmArmMode.VACATION, "1234"))
        assertEquals(AlarmPanelCommands.armCustomBypass("1234"), AlarmPanelCommands.arm(AlarmArmMode.CUSTOM_BYPASS, "1234"))
    }
}
