package com.matiasnl.hakiosk.data.device

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MqttConfigTest {
    @Test
    fun toStringRedactsPassword() {
        val text = MqttConfig(host = "broker.lan", username = "kiosk", password = "broker-secret", deviceName = "Tablet").toString()
        assertFalse(text.contains("broker-secret"))
        assertTrue(text.contains("broker.lan"))
    }
}
