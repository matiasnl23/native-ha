package com.matiasnl.hakiosk.data.device

import com.matiasnl.hakiosk.data.device.fake.InMemoryMqttConfigStore
import com.matiasnl.hakiosk.data.device.mqtt.MqttAuthException
import com.matiasnl.hakiosk.data.device.mqtt.MqttClientFactory
import com.matiasnl.hakiosk.data.device.mqtt.MqttConnectParams
import com.matiasnl.hakiosk.data.device.mqtt.MqttConnectionManager
import com.matiasnl.hakiosk.data.device.mqtt.MqttMessage
import com.matiasnl.hakiosk.data.device.mqtt.MqttQosLevel
import com.matiasnl.hakiosk.data.device.mqtt.MqttSession
import com.matiasnl.hakiosk.data.device.mqtt.MqttWill
import com.matiasnl.hakiosk.data.device.mqtt.NetworkMonitor
import com.matiasnl.hakiosk.data.device.mqtt.RemoteControlServiceController
import com.matiasnl.hakiosk.data.ha.ws.Backoff
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.UnknownHostException

class MqttConnectionManagerTest {

    private class FakeSession(val onMessage: (MqttMessage) -> Unit) : MqttSession {
        val closed = CompletableDeferred<Throwable>()
        val published = mutableListOf<Triple<String, String, Boolean>>()
        val subscribed = mutableListOf<String>()
        val unsubscribed = mutableListOf<String>()
        var disconnected = false

        override suspend fun awaitClosed(): Throwable = closed.await()

        override suspend fun publish(topic: String, payload: ByteArray, qos: MqttQosLevel, retain: Boolean) {
            if (closed.isCompleted) throw IOException("closed")
            published += Triple(topic, payload.toString(Charsets.UTF_8), retain)
        }

        override suspend fun subscribe(topicFilter: String, qos: MqttQosLevel) {
            subscribed += topicFilter
        }

        override suspend fun unsubscribe(topicFilter: String) {
            unsubscribed += topicFilter
        }

        override suspend fun disconnect() {
            disconnected = true
            closed.complete(IOException("disconnected by client"))
        }

        fun drop(cause: Throwable = IOException("Connection reset")) {
            closed.complete(cause)
        }
    }

    private class FakeClientFactory : MqttClientFactory {
        val connects = mutableListOf<MqttConnectParams>()
        val sessions = mutableListOf<FakeSession>()

        /** Consumed one per connect: an exception to throw, or [HANG] to never complete. Empty = success. */
        val outcomes = mutableListOf<Throwable>()

        override suspend fun connect(params: MqttConnectParams, onMessage: (MqttMessage) -> Unit): MqttSession {
            connects += params
            val outcome = outcomes.removeFirstOrNull()
            if (outcome === HANG) awaitCancellation()
            if (outcome != null) throw outcome
            return FakeSession(onMessage).also { sessions += it }
        }

        companion object {
            val HANG = Exception("sentinel: connect never completes")
        }
    }

    private class FakeServiceController : RemoteControlServiceController {
        var running = false
        var starts = 0
        override fun startService() {
            starts++
            running = true
        }

        override fun stopService() {
            running = false
        }
    }

    private val config = MqttConfig(host = "broker.lan", username = "kiosk", password = "secret", deviceName = "Tablet")
    private val availability = "hakiosk/$DEVICE_ID/availability"

    private val store = InMemoryMqttConfigStore()
    private val factory = FakeClientFactory()
    private val network = MutableStateFlow<Long?>(1L)
    private val service = FakeServiceController()

    /** Deterministic backoff: 1s, 2s, 4s... capped at 60s. */
    private val backoff = Backoff { attempt -> minOf(1_000L shl attempt.coerceAtMost(6), 60_000L) }

    private fun TestScope.manager() = MqttConnectionManager(
        configStore = store,
        deviceIdProvider = { DEVICE_ID },
        clientFactory = factory,
        networkMonitor = NetworkMonitor { network },
        serviceController = service,
        scope = backgroundScope,
        backoff = backoff,
    )

    private val MqttConnectionManager.state get() = connectionState.value

    @Test
    fun startWithoutConfigIsDisabledWithoutServiceOrConnection() = runTest {
        val manager = manager()
        manager.start()
        runCurrent()

        assertEquals(MqttConnectionState.Disabled, manager.state)
        assertEquals(0, service.starts)
        assertTrue(factory.connects.isEmpty())
    }

    @Test
    fun startConnectsWithLastWillAndPublishesOnlineRetained() = runTest {
        store.save(config)
        val manager = manager()
        manager.start()
        runCurrent()

        assertEquals(MqttConnectionState.Connected, manager.state)
        assertTrue(service.running)
        val params = factory.connects.single()
        assertEquals("broker.lan", params.host)
        assertEquals(1883, params.port)
        assertEquals("hakiosk-$DEVICE_ID", params.clientId)
        assertEquals("kiosk", params.username)
        assertEquals("secret", params.password)
        assertEquals(60, params.keepAliveSeconds)
        assertEquals(MqttWill(availability, "offline", MqttQosLevel.AT_LEAST_ONCE, retain = true), params.will)
        assertEquals(listOf(Triple(availability, "online", true)), factory.sessions.single().published)
        assertFalse(params.toString().contains("secret"))
    }

    @Test
    fun startIsIdempotent() = runTest {
        store.save(config)
        val manager = manager()
        manager.start()
        runCurrent()
        manager.start()
        manager.start()
        runCurrent()

        assertEquals(1, factory.connects.size)
        assertEquals(MqttConnectionState.Connected, manager.state)
    }

    @Test
    fun connectFailuresRetryWithExponentialBackoff() = runTest {
        store.save(config)
        factory.outcomes += listOf(UnknownHostException("broker.lan"), IOException("refused"))
        val manager = manager()
        manager.start()
        runCurrent()

        assertEquals(MqttConnectionState.Disconnected("Unknown host: broker.lan", 1_000), manager.state)
        advanceTimeBy(999)
        runCurrent()
        assertEquals(1, factory.connects.size)

        advanceTimeBy(1)
        runCurrent()
        assertEquals(2, factory.connects.size)
        assertEquals(2_000L, (manager.state as MqttConnectionState.Disconnected).retryInMillis)

        advanceTimeBy(2_000)
        runCurrent()
        assertEquals(3, factory.connects.size)
        assertEquals(MqttConnectionState.Connected, manager.state)
    }

    @Test
    fun backoffResetsAfterSuccessfulConnect() = runTest {
        store.save(config)
        factory.outcomes += listOf(IOException("a"), IOException("b"))
        val manager = manager()
        manager.start()
        advanceTimeBy(3_001)
        runCurrent()
        assertEquals(MqttConnectionState.Connected, manager.state)

        factory.sessions.single().drop()
        runCurrent()

        assertEquals(MqttConnectionState.Disconnected("Connection reset", 1_000), manager.state)
        advanceTimeBy(1_001)
        runCurrent()
        assertEquals(MqttConnectionState.Connected, manager.state)
        assertEquals(2, factory.sessions.size)
    }

    @Test
    fun authFailureStopsRetryingUntilConfigChanges() = runTest {
        store.save(config)
        factory.outcomes += MqttAuthException("Not authorized")
        val manager = manager()
        manager.start()
        runCurrent()

        assertEquals(MqttConnectionState.AuthFailed("Not authorized"), manager.state)
        advanceTimeBy(30 * 60_000L)
        runCurrent()
        assertEquals(1, factory.connects.size)

        store.save(config.copy(password = "fixed"))
        runCurrent()
        assertEquals(2, factory.connects.size)
        assertEquals("fixed", factory.connects.last().password)
        assertEquals(MqttConnectionState.Connected, manager.state)
    }

    @Test
    fun startAgainAfterAuthFailureRetriesOnce() = runTest {
        store.save(config)
        factory.outcomes += MqttAuthException("Bad user name or password")
        val manager = manager()
        manager.start()
        runCurrent()
        assertTrue(manager.state is MqttConnectionState.AuthFailed)

        manager.start()
        runCurrent()
        assertEquals(2, factory.connects.size)
        assertEquals(MqttConnectionState.Connected, manager.state)
    }

    @Test
    fun connectionLostReconnectsAfterBackoff() = runTest {
        store.save(config)
        val manager = manager()
        manager.start()
        runCurrent()

        factory.sessions.single().drop(IOException("Broken pipe"))
        runCurrent()
        assertEquals(MqttConnectionState.Disconnected("Broken pipe", 1_000), manager.state)
        assertEquals(1, factory.connects.size)

        advanceTimeBy(1_001)
        runCurrent()
        assertEquals(MqttConnectionState.Connected, manager.state)
        assertEquals(listOf(Triple(availability, "online", true)), factory.sessions[1].published)
    }

    @Test
    fun configChangeDisconnectsCleanlyAndReconnects() = runTest {
        store.save(config)
        val manager = manager()
        manager.start()
        runCurrent()
        val first = factory.sessions.single()

        store.save(config.copy(host = "other.lan", port = 8883, useTls = true))
        runCurrent()

        assertEquals(Triple(availability, "offline", true), first.published.last())
        assertTrue(first.disconnected)
        assertEquals("other.lan", factory.connects.last().host)
        assertTrue(factory.connects.last().useTls)
        assertEquals(MqttConnectionState.Connected, manager.state)
    }

    @Test
    fun clearingConfigDisablesAndStopsService() = runTest {
        store.save(config)
        val manager = manager()
        manager.start()
        runCurrent()
        val session = factory.sessions.single()

        store.clear()
        runCurrent()

        assertEquals(MqttConnectionState.Disabled, manager.state)
        assertFalse(service.running)
        assertEquals(Triple(availability, "offline", true), session.published.last())
        assertTrue(session.disconnected)
        advanceTimeBy(120_000)
        runCurrent()
        assertEquals(1, factory.connects.size)
    }

    @Test
    fun stopPublishesOfflineDisconnectsAndStaysDisabled() = runTest {
        store.save(config)
        val manager = manager()
        manager.start()
        runCurrent()
        val session = factory.sessions.single()

        manager.stop()
        runCurrent()

        assertEquals(MqttConnectionState.Disabled, manager.state)
        assertFalse(service.running)
        assertEquals(Triple(availability, "offline", true), session.published.last())
        assertTrue(session.disconnected)
        advanceTimeBy(120_000)
        runCurrent()
        assertEquals(1, factory.connects.size)
        assertEquals(MqttConnectionState.Disabled, manager.state)
    }

    @Test
    fun startAfterStopConnectsAgain() = runTest {
        store.save(config)
        val manager = manager()
        manager.start()
        runCurrent()
        manager.stop()
        manager.start()
        runCurrent()

        assertEquals(2, factory.connects.size)
        assertEquals(MqttConnectionState.Connected, manager.state)
        // The old session finished its "offline" before the new one said "online".
        assertTrue(factory.sessions[0].disconnected)
        assertEquals(Triple(availability, "online", true), factory.sessions[1].published.last())
    }

    @Test
    fun networkLossWaitsForNetworkAndReconnectsImmediately() = runTest {
        store.save(config)
        val manager = manager()
        manager.start()
        runCurrent()
        val first = factory.sessions.single()

        network.value = null
        runCurrent()
        assertTrue(first.disconnected)
        assertEquals(MqttConnectionState.Disconnected(MqttConnectionManager.NO_NETWORK_MESSAGE, 0), manager.state)
        advanceTimeBy(300_000)
        runCurrent()
        assertEquals(1, factory.connects.size)

        network.value = 2L
        runCurrent()
        assertEquals(2, factory.connects.size)
        assertEquals(MqttConnectionState.Connected, manager.state)
    }

    @Test
    fun defaultNetworkSwitchReconnectsWithoutWaitingForKeepalive() = runTest {
        store.save(config)
        val manager = manager()
        manager.start()
        runCurrent()

        network.value = 2L
        runCurrent()

        assertTrue(factory.sessions[0].disconnected)
        assertEquals(2, factory.connects.size)
        assertEquals(MqttConnectionState.Connected, manager.state)
    }

    @Test
    fun networkReturningCutsTheBackoffShort() = runTest {
        store.save(config)
        factory.outcomes += List(5) { IOException("unreachable") }
        val manager = manager()
        manager.start()
        advanceTimeBy(1_000 + 2_000 + 4_000 + 1)
        runCurrent()
        assertEquals(4, factory.connects.size)
        assertEquals(8_000L, (manager.state as MqttConnectionState.Disconnected).retryInMillis)

        network.value = 7L
        runCurrent()
        assertEquals(5, factory.connects.size)
    }

    @Test
    fun subscriptionsReceiveMatchingMessagesAndSurviveReconnects() = runTest {
        store.save(config)
        val manager = manager()
        val received = mutableListOf<String>()
        val collector = backgroundScope.launch {
            manager.subscribe("hakiosk/$DEVICE_ID/+/set").collect { received += "${it.topic}=${it.payloadText}" }
        }
        manager.start()
        runCurrent()
        val first = factory.sessions.single()
        assertEquals(listOf("hakiosk/$DEVICE_ID/+/set"), first.subscribed)

        first.onMessage(MqttMessage("hakiosk/$DEVICE_ID/screen/set", "ON".toByteArray(), retained = false))
        first.onMessage(MqttMessage("hakiosk/$DEVICE_ID/screen/state", "ON".toByteArray(), retained = false))
        runCurrent()
        assertEquals(listOf("hakiosk/$DEVICE_ID/screen/set=ON"), received)

        first.drop()
        advanceTimeBy(1_001)
        runCurrent()
        assertEquals(listOf("hakiosk/$DEVICE_ID/+/set"), factory.sessions[1].subscribed)

        collector.cancel()
        runCurrent()
        assertEquals(listOf("hakiosk/$DEVICE_ID/+/set"), factory.sessions[1].unsubscribed)
    }

    @Test
    fun subscribingWhileConnectedSubscribesRightAway() = runTest {
        store.save(config)
        val manager = manager()
        manager.start()
        runCurrent()

        backgroundScope.launch { manager.subscribe("homeassistant/status").collect { } }
        runCurrent()

        assertEquals(listOf("homeassistant/status"), factory.sessions.single().subscribed)
    }

    @Test
    fun publishReturnsFalseWhenNotConnected() = runTest {
        store.save(config)
        factory.outcomes += IOException("down")
        val manager = manager()
        manager.start()
        runCurrent()

        assertFalse(manager.publish("hakiosk/$DEVICE_ID/x", "1"))

        advanceTimeBy(1_001)
        runCurrent()
        assertTrue(manager.publish("hakiosk/$DEVICE_ID/x", "1", retain = true))
        assertEquals(Triple("hakiosk/$DEVICE_ID/x", "1", true), factory.sessions.single().published.last())
    }

    @Test
    fun topicsUseTheStoredDeviceId() = runTest {
        assertEquals("hakiosk/$DEVICE_ID/availability", manager().topics().availability)
    }

    @Test
    fun testConnectionSuccessUsesSeparateClientWithoutWillAndDisconnects() = runTest {
        val manager = manager()

        assertEquals(MqttTestResult.Success, manager.testConnection(config))

        val params = factory.connects.single()
        assertNull(params.will)
        assertNotEquals("hakiosk-$DEVICE_ID", params.clientId)
        assertTrue(factory.sessions.single().disconnected)
        assertEquals(MqttConnectionState.Disabled, manager.state)
    }

    @Test
    fun testConnectionMapsFailures() = runTest {
        val manager = manager()
        factory.outcomes += listOf(
            MqttAuthException("Bad user name or password"),
            UnknownHostException("broker.lan"),
            IOException("wrapper", javax.net.ssl.SSLHandshakeException("handshake failed")),
        )

        assertEquals(MqttTestResult.AuthFailed("Bad user name or password"), manager.testConnection(config))
        assertEquals(MqttTestResult.Unreachable("Unknown host: broker.lan"), manager.testConnection(config))
        assertEquals(MqttTestResult.Unreachable("wrapper"), manager.testConnection(config))
    }

    @Test
    fun testConnectionTimesOut() = runTest {
        val manager = manager()
        factory.outcomes += FakeClientFactory.HANG

        assertEquals(MqttTestResult.Unreachable("Timed out"), manager.testConnection(config))
    }

    @Test
    fun testConnectionRejectsBlankHost() = runTest {
        val result = manager().testConnection(config.copy(host = " "))
        assertTrue(result is MqttTestResult.Unreachable)
        assertTrue(factory.connects.isEmpty())
    }

    private companion object {
        const val DEVICE_ID = "0123456789abcdef"
    }
}
