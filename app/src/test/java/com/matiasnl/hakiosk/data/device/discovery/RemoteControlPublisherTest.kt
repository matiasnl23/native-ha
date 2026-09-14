package com.matiasnl.hakiosk.data.device.discovery

import com.matiasnl.hakiosk.data.device.CameraOption
import com.matiasnl.hakiosk.data.device.InMemoryRemoteControlBridge
import com.matiasnl.hakiosk.data.device.MqttConfig
import com.matiasnl.hakiosk.data.device.RemoteCommand
import com.matiasnl.hakiosk.data.device.ViewOption
import com.matiasnl.hakiosk.data.device.fake.FakeBatterySource
import com.matiasnl.hakiosk.data.device.fake.FakeMqttMessaging
import com.matiasnl.hakiosk.data.device.fake.InMemoryMqttConfigStore
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteControlPublisherTest {
    private val messaging = FakeMqttMessaging(DEVICE_ID)
    private val bridge = InMemoryRemoteControlBridge()
    private val configStore = InMemoryMqttConfigStore(MqttConfig(host = "broker.lan", deviceName = "Tablet cocina"))
    private val battery = FakeBatterySource(BatteryState(percent = 80, charging = false))
    private val received = mutableListOf<RemoteCommand>()

    private fun TestScope.publisher(): RemoteControlPublisher {
        backgroundScope.launch { bridge.commands.collect { received += it } }
        return RemoteControlPublisher(
            messaging = messaging,
            bridge = bridge,
            configStore = configStore,
            batterySource = battery,
            deviceModel = "SM-T500",
            appVersion = "1.0",
            scope = backgroundScope,
        )
    }

    private fun topic(suffix: String) = "hakiosk/$DEVICE_ID/$suffix"
    private fun configTopic(component: String, objectId: String) = "homeassistant/$component/$DEVICE_ID/$objectId/config"

    private fun TestScope.connect(publisher: RemoteControlPublisher) {
        publisher.start()
        runCurrent()
        messaging.setConnected(true)
        runCurrent()
    }

    @Test
    fun connectingPublishesAllDiscoveryConfigsAndStatesRetained() = runTest {
        connect(publisher())

        // 12 entities: 10 with a state topic, all 12 with a discovery config.
        assertEquals(22, messaging.published.size)
        assertTrue(messaging.published.all { it.retain })
        assertNotNull(messaging.lastPublished(configTopic("switch", "screen")))
        assertNotNull(messaging.lastPublished(configTopic("number", "brightness")))
        assertNotNull(messaging.lastPublished(configTopic("select", "view")))
        assertNotNull(messaging.lastPublished(configTopic("button", "main_view")))
        assertNotNull(messaging.lastPublished(configTopic("sensor", "battery")))
        assertNotNull(messaging.lastPublished(configTopic("binary_sensor", "charging")))
        assertEquals("ON", messaging.lastPublished(topic("screen/state")))
        assertEquals("100", messaging.lastPublished(topic("brightness/state")))
        assertEquals("80", messaging.lastPublished(topic("battery/state")))
        assertEquals("OFF", messaging.lastPublished(topic("charging/state")))
        assertEquals(NO_CAMERA_OPTION, messaging.lastPublished(topic("camera/state")))
        assertEquals(DiscoveryPayloads.PAYLOAD_NONE, messaging.lastPublished(topic("last_interaction/state")))
    }

    @Test
    fun reconnectingRepublishesEverything() = runTest {
        val publisher = publisher()
        connect(publisher)
        messaging.published.clear()

        messaging.setConnected(false)
        runCurrent()
        assertEquals(0, messaging.published.size)

        messaging.setConnected(true)
        runCurrent()
        assertEquals(22, messaging.published.size)
    }

    @Test
    fun onlyTheChangedStateIsPublishedAfterConnect() = runTest {
        connect(publisher())
        messaging.published.clear()

        bridge.updateUiState { it.copy(brightnessPercent = 55) }
        advanceTimeBy(DEBOUNCE_WAIT)
        runCurrent()

        assertEquals(listOf(FakeMqttMessaging.Published(topic("brightness/state"), "55", true)), messaging.published)
    }

    @Test
    fun burstsOfChangesAreCoalescedByDebounce() = runTest {
        connect(publisher())
        messaging.published.clear()

        bridge.updateUiState { it.copy(brightnessPercent = 10) }
        advanceTimeBy(100)
        bridge.updateUiState { it.copy(brightnessPercent = 20) }
        advanceTimeBy(100)
        bridge.updateUiState { it.copy(brightnessPercent = 30) }
        advanceTimeBy(DEBOUNCE_WAIT)
        runCurrent()

        val brightnessPublishes = messaging.published.filter { it.topic == topic("brightness/state") }
        assertEquals(1, brightnessPublishes.size)
        assertEquals("30", brightnessPublishes.single().payload)
    }

    @Test
    fun nothingIsPublishedWhileDisconnected() = runTest {
        val publisher = publisher()
        publisher.start()
        runCurrent()

        bridge.updateUiState { it.copy(brightnessPercent = 55) }
        advanceTimeBy(DEBOUNCE_WAIT)
        runCurrent()

        assertEquals(0, messaging.published.size)
    }

    @Test
    fun viewOptionsChangeRepublishesOnlyThatSelectConfig() = runTest {
        connect(publisher())
        messaging.published.clear()

        bridge.updateUiState { it.copy(views = listOf(ViewOption("a", "Cocina"), ViewOption("b", "Living"))) }
        advanceTimeBy(DEBOUNCE_WAIT)
        runCurrent()

        assertEquals(1, messaging.published.size)
        val config = Json.parseToJsonElement(messaging.published.single().payload).jsonObject
        assertEquals(listOf("Cocina", "Living"), config.getValue("options").jsonArray.map { it.jsonPrimitive.content })
        assertEquals(configTopic("select", "view"), messaging.published.single().topic)
    }

    @Test
    fun duplicateCameraNamesAreDisambiguatedEndToEnd() = runTest {
        val publisher = publisher()
        publisher.start()
        runCurrent()
        bridge.updateUiState { it.copy(cameras = listOf(CameraOption("camera.a", "Patio"), CameraOption("camera.b", "Patio"))) }
        runCurrent()
        messaging.setConnected(true)
        runCurrent()

        val cameraConfig = Json.parseToJsonElement(messaging.lastPublished(configTopic("select", "camera"))!!).jsonObject
        val options = cameraConfig.getValue("options").jsonArray.map { it.jsonPrimitive.content }
        assertEquals(listOf(NO_CAMERA_OPTION, "Patio", "Patio (2)"), options)

        messaging.emit(topic("camera/set"), "Patio (2)")
        runCurrent()
        assertEquals(listOf(RemoteCommand.OpenCamera("camera.b", closeAfterSeconds = null)), received)
    }

    @Test
    fun lastInteractionStateIsIsoOffsetTimestampOrNoneWhenNull() = runTest {
        connect(publisher())
        assertEquals(DiscoveryPayloads.PAYLOAD_NONE, messaging.lastPublished(topic("last_interaction/state")))
        messaging.published.clear()

        val epoch = 1_700_000_000_000L
        bridge.updateUiState { it.copy(lastInteractionEpochMillis = epoch) }
        advanceTimeBy(DEBOUNCE_WAIT)
        runCurrent()

        val published = messaging.lastPublished(topic("last_interaction/state"))!!
        val parsedBack = OffsetDateTime.parse(published, DateTimeFormatter.ISO_OFFSET_DATE_TIME)
        assertEquals(epoch, parsedBack.toInstant().toEpochMilli())
    }

    // --- Entity commands ------------------------------------------------------------------------

    @Test
    fun screenSwitchCommandTogglesScreen() = runTest {
        connect(publisher())

        messaging.emit(topic("screen/set"), "OFF")
        runCurrent()

        assertEquals(listOf(RemoteCommand.SetScreenOn(false)), received)
    }

    @Test
    fun screenSwitchInvalidPayloadIsIgnored() = runTest {
        connect(publisher())

        messaging.emit(topic("screen/set"), "TOGGLE")
        runCurrent()

        assertTrue(received.isEmpty())
    }

    @Test
    fun brightnessCommandIsClamped() = runTest {
        connect(publisher())

        messaging.emit(topic("brightness/set"), "500")
        runCurrent()

        assertEquals(listOf(RemoteCommand.SetBrightness(100)), received)
    }

    @Test
    fun screenOffTimeoutCommandIsClamped() = runTest {
        connect(publisher())

        messaging.emit(topic("screen_off_timeout/set"), "9999")
        runCurrent()

        assertEquals(listOf(RemoteCommand.SetScreenOffTimeout(240)), received)
    }

    @Test
    fun cameraCloseAfterCommandIsClamped() = runTest {
        connect(publisher())

        messaging.emit(topic("camera_close_after/set"), "-5")
        runCurrent()

        assertEquals(listOf(RemoteCommand.SetCameraCloseAfter(0)), received)
    }

    @Test
    fun viewSelectCommandResolvesKnownNameToId() = runTest {
        val publisher = publisher()
        publisher.start()
        runCurrent()
        bridge.updateUiState { it.copy(views = listOf(ViewOption("home", "Inicio"), ViewOption("kitchen", "Cocina"))) }
        runCurrent()

        messaging.emit(topic("view/set"), "Cocina")
        runCurrent()

        assertEquals(listOf(RemoteCommand.ShowView("kitchen")), received)
    }

    @Test
    fun viewSelectCommandWithUnknownNameIsIgnored() = runTest {
        val publisher = publisher()
        publisher.start()
        runCurrent()
        bridge.updateUiState { it.copy(views = listOf(ViewOption("home", "Inicio"))) }
        runCurrent()

        messaging.emit(topic("view/set"), "Nonexistent")
        runCurrent()

        assertTrue(received.isEmpty())
    }

    @Test
    fun mainViewButtonDispatchesRegardlessOfPayload() = runTest {
        connect(publisher())

        messaging.emit(topic("main_view/set"), "PRESS")
        runCurrent()

        assertEquals(listOf(RemoteCommand.ShowMainView), received)
    }

    @Test
    fun reloadButtonDispatches() = runTest {
        connect(publisher())

        messaging.emit(topic("reload/set"), "PRESS")
        runCurrent()

        assertEquals(listOf(RemoteCommand.Reload), received)
    }

    @Test
    fun cameraSelectNingunaClosesCamera() = runTest {
        connect(publisher())

        messaging.emit(topic("camera/set"), NO_CAMERA_OPTION)
        runCurrent()

        assertEquals(listOf(RemoteCommand.CloseCamera), received)
    }

    @Test
    fun cameraSelectKnownNameOpensCamera() = runTest {
        val publisher = publisher()
        publisher.start()
        runCurrent()
        bridge.updateUiState { it.copy(cameras = listOf(CameraOption("camera.front", "Frente"))) }
        runCurrent()

        messaging.emit(topic("camera/set"), "Frente")
        runCurrent()

        assertEquals(listOf(RemoteCommand.OpenCamera("camera.front", closeAfterSeconds = null)), received)
    }

    @Test
    fun cameraSelectUnknownNameIsIgnored() = runTest {
        connect(publisher())

        messaging.emit(topic("camera/set"), "Unknown camera")
        runCurrent()

        assertTrue(received.isEmpty())
    }

    @Test
    fun sensorEntitiesIgnoreCommandsSentToTheirSetTopic() = runTest {
        connect(publisher())

        messaging.emit(topic("battery/set"), "42")
        runCurrent()

        assertTrue(received.isEmpty())
    }

    // --- JSON command topic ----------------------------------------------------------------------

    @Test
    fun jsonCommandOpenCameraDispatches() = runTest {
        connect(publisher())

        messaging.emit(topic("command"), """{"command":"open_camera","entity_id":"camera.doorbell","close_after":30}""")
        runCurrent()

        assertEquals(listOf(RemoteCommand.OpenCamera("camera.doorbell", 30)), received)
    }

    @Test
    fun jsonCommandCloseCameraDispatches() = runTest {
        connect(publisher())

        messaging.emit(topic("command"), """{"command":"close_camera"}""")
        runCurrent()

        assertEquals(listOf(RemoteCommand.CloseCamera), received)
    }

    @Test
    fun jsonCommandShowViewAcceptsEitherIdOrName() = runTest {
        val publisher = publisher()
        publisher.start()
        runCurrent()
        bridge.updateUiState { it.copy(views = listOf(ViewOption("home", "Inicio"))) }
        runCurrent()

        messaging.emit(topic("command"), """{"command":"show_view","view":"home"}""")
        runCurrent()
        messaging.emit(topic("command"), """{"command":"show_view","view":"Inicio"}""")
        runCurrent()

        assertEquals(listOf(RemoteCommand.ShowView("home"), RemoteCommand.ShowView("home")), received)
    }

    @Test
    fun jsonCommandShowViewWithUnknownReferenceIsIgnored() = runTest {
        connect(publisher())

        messaging.emit(topic("command"), """{"command":"show_view","view":"nope"}""")
        runCurrent()

        assertTrue(received.isEmpty())
    }

    @Test
    fun jsonCommandScreenAndBrightnessDispatchClamped() = runTest {
        connect(publisher())

        messaging.emit(topic("command"), """{"command":"screen","on":true}""")
        messaging.emit(topic("command"), """{"command":"brightness","value":250}""")
        messaging.emit(topic("command"), """{"command":"reload"}""")
        messaging.emit(topic("command"), """{"command":"main_view"}""")
        runCurrent()

        assertEquals(
            listOf(
                RemoteCommand.SetScreenOn(true),
                RemoteCommand.SetBrightness(100),
                RemoteCommand.Reload,
                RemoteCommand.ShowMainView,
            ),
            received,
        )
    }

    @Test
    fun jsonCommandMalformedPayloadIsIgnored() = runTest {
        connect(publisher())

        messaging.emit(topic("command"), "not json")
        runCurrent()

        assertTrue(received.isEmpty())
    }

    private companion object {
        const val DEVICE_ID = "0123456789abcdef"
        const val DEBOUNCE_WAIT = RemoteControlPublisher.DEBOUNCE_MILLIS + 1
    }
}
