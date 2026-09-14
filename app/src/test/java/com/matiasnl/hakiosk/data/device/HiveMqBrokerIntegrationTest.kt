package com.matiasnl.hakiosk.data.device

import com.matiasnl.hakiosk.data.device.fake.InMemoryMqttConfigStore
import com.matiasnl.hakiosk.data.device.mqtt.HiveMqClientFactory
import com.matiasnl.hakiosk.data.device.mqtt.MqttAuthException
import com.matiasnl.hakiosk.data.device.mqtt.MqttConnectParams
import com.matiasnl.hakiosk.data.device.mqtt.MqttConnectionManager
import com.matiasnl.hakiosk.data.device.mqtt.MqttMessage
import com.matiasnl.hakiosk.data.device.mqtt.MqttQosLevel
import com.matiasnl.hakiosk.data.device.mqtt.NetworkMonitor
import com.matiasnl.hakiosk.data.device.mqtt.RemoteControlServiceController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.util.UUID

/**
 * Exercises [HiveMqClientFactory] and [MqttConnectionManager] against a real broker. Skipped unless
 * `MQTT_IT_HOST` is set, so the normal build never needs a network or a broker. Example (throwaway
 * local Mosquitto, never the user's broker):
 *
 * ```
 * MQTT_IT_HOST=127.0.0.1 MQTT_IT_PORT=18830 MQTT_IT_AUTH_PORT=18831 MQTT_IT_USER=kiosk \
 *   MQTT_IT_PASSWORD=... ./gradlew testDebugUnitTest --tests '*HiveMqBrokerIntegrationTest'
 * ```
 * `MQTT_IT_AUTH_PORT` is a listener with `allow_anonymous false` and a password file.
 */
class HiveMqBrokerIntegrationTest {
    private val host = System.getenv("MQTT_IT_HOST")
    private val port = System.getenv("MQTT_IT_PORT")?.toInt() ?: 1883
    private val authPort = System.getenv("MQTT_IT_AUTH_PORT")?.toInt()
    private val user = System.getenv("MQTT_IT_USER")
    private val password = System.getenv("MQTT_IT_PASSWORD")
    private val factory = HiveMqClientFactory()

    @Before
    fun requireBroker() {
        assumeTrue("MQTT_IT_HOST not set", !host.isNullOrBlank())
    }

    private fun params(
        port: Int = this.port,
        username: String? = null,
        password: String? = null,
    ) = MqttConnectParams(
        host = host!!,
        port = port,
        useTls = false,
        clientId = "hakiosk-it-" + UUID.randomUUID().toString().take(8),
        username = username,
        password = password,
        keepAliveSeconds = 60,
        connectTimeoutMillis = 5_000,
        will = null,
    )

    @Test
    fun publishAndSubscribeRoundTrip() = runBlocking {
        val received = Channel<MqttMessage>(Channel.UNLIMITED)
        val topic = "hakiosk-it/${UUID.randomUUID()}/cmd"
        val subscriber = factory.connect(params()) { received.trySend(it) }
        val publisher = factory.connect(params()) { }
        try {
            subscriber.subscribe("hakiosk-it/+/cmd", MqttQosLevel.AT_LEAST_ONCE)
            publisher.publish(topic, "ON".toByteArray(), MqttQosLevel.AT_LEAST_ONCE, retain = false)
            val message = withTimeout(5_000) { received.receive() }
            assertEquals(topic, message.topic)
            assertEquals("ON", message.payloadText)
        } finally {
            subscriber.disconnect()
            publisher.disconnect()
        }
    }

    @Test
    fun wrongPasswordIsReportedAsAuthFailure() = runBlocking {
        assumeTrue("MQTT_IT_AUTH_PORT not set", authPort != null)
        val error = runCatching { factory.connect(params(port = authPort!!, username = "kiosk", password = "wrong")) { } }
            .exceptionOrNull()
        assertTrue("got $error", error is MqttAuthException)
    }

    @Test
    fun rightPasswordConnects() = runBlocking {
        assumeTrue("MQTT_IT_AUTH_PORT/USER/PASSWORD not set", authPort != null && user != null && password != null)
        factory.connect(params(port = authPort!!, username = user, password = password)) { }.disconnect()
    }

    @Test
    fun closedPortIsANetworkFailure() = runBlocking {
        val error = runCatching { factory.connect(params(port = 1)) { } }.exceptionOrNull()
        assertTrue("got $error", error != null && error !is MqttAuthException)
    }

    @Test
    fun managerPublishesAvailabilityOnlineAndOfflineOnStop() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val deviceId = UUID.randomUUID().toString().replace("-", "").take(16)
        val store = InMemoryMqttConfigStore(MqttConfig(host = host!!, port = port, deviceName = "IT"))
        val manager = MqttConnectionManager(
            configStore = store,
            deviceIdProvider = { deviceId },
            clientFactory = factory,
            networkMonitor = NetworkMonitor { MutableStateFlow(1L) },
            serviceController = object : RemoteControlServiceController {
                override fun startService() = Unit
                override fun stopService() = Unit
            },
            scope = scope,
        )
        val availability = "hakiosk/$deviceId/availability"
        val received = Channel<String>(Channel.UNLIMITED)
        val observer = factory.connect(params()) { if (it.topic == availability) received.trySend(it.payloadText) }
        try {
            observer.subscribe(availability, MqttQosLevel.AT_LEAST_ONCE)
            manager.start()
            withTimeout(10_000) { manager.connectionState.first { it == MqttConnectionState.Connected } }
            assertEquals("online", withTimeout(5_000) { received.receive() })

            manager.stop()
            assertEquals("offline", withTimeout(5_000) { received.receive() })

            val test = manager.testConnection(MqttConfig(host = host, port = port, deviceName = "IT"))
            assertEquals(MqttTestResult.Success, test)
        } finally {
            // Clear the retained availability so the throwaway broker doesn't accumulate test devices.
            observer.publish(availability, ByteArray(0), MqttQosLevel.AT_LEAST_ONCE, retain = true)
            observer.disconnect()
            scope.cancel()
        }
    }
}
