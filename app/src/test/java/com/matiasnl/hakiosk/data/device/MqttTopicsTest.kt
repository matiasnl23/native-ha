package com.matiasnl.hakiosk.data.device

import com.matiasnl.hakiosk.data.device.mqtt.MqttTopics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MqttTopicsTest {
    @Test
    fun schemeIsRootedAtDeviceId() {
        val topics = MqttTopics("0123456789abcdef")
        assertEquals("hakiosk/0123456789abcdef/availability", topics.availability)
        assertEquals("hakiosk/0123456789abcdef/screen/set", topics.topic("screen/set"))
        assertEquals("hakiosk-0123456789abcdef", topics.clientId)
    }

    @Test
    fun exactFilterMatchesOnlyThatTopic() {
        assertTrue(MqttTopics.matches("a/b/c", "a/b/c"))
        assertFalse(MqttTopics.matches("a/b/c", "a/b"))
        assertFalse(MqttTopics.matches("a/b", "a/b/c"))
    }

    @Test
    fun plusMatchesExactlyOneLevel() {
        assertTrue(MqttTopics.matches("hakiosk/id/+/set", "hakiosk/id/screen/set"))
        assertFalse(MqttTopics.matches("hakiosk/id/+/set", "hakiosk/id/a/b/set"))
        assertTrue(MqttTopics.matches("a/+", "a/"))
        assertFalse(MqttTopics.matches("a/+", "a"))
    }

    @Test
    fun hashMatchesParentAndAnyDepth() {
        assertTrue(MqttTopics.matches("hakiosk/id/#", "hakiosk/id"))
        assertTrue(MqttTopics.matches("hakiosk/id/#", "hakiosk/id/x/y/z"))
        assertTrue(MqttTopics.matches("#", "anything/at/all"))
        assertFalse(MqttTopics.matches("hakiosk/id/#", "hakiosk/other/x"))
        assertFalse(MqttTopics.matches("a/#/b", "a/x/b"))
    }

    @Test
    fun leadingWildcardsDoNotMatchDollarTopics() {
        assertFalse(MqttTopics.matches("#", "\$SYS/broker/uptime"))
        assertFalse(MqttTopics.matches("+/broker/uptime", "\$SYS/broker/uptime"))
        assertTrue(MqttTopics.matches("\$SYS/#", "\$SYS/broker/uptime"))
    }
}
