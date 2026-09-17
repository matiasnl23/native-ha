package com.matiasnl.hakiosk.data.config

import com.matiasnl.hakiosk.data.dashboard.DashboardLayout
import com.matiasnl.hakiosk.data.dashboard.DashboardLayoutStore
import com.matiasnl.hakiosk.data.dashboard.DashboardTile
import com.matiasnl.hakiosk.data.dashboard.DashboardView
import com.matiasnl.hakiosk.data.dashboard.DashboardViewPreferences
import com.matiasnl.hakiosk.data.dashboard.InMemoryDashboardLayoutStore
import com.matiasnl.hakiosk.data.dashboard.InMemoryDashboardViewPreferencesStore
import com.matiasnl.hakiosk.data.dashboard.StoredDashboardLayout
import com.matiasnl.hakiosk.data.dashboard.TileContent
import com.matiasnl.hakiosk.data.device.MqttConfig
import com.matiasnl.hakiosk.data.device.fake.InMemoryMqttConfigStore
import com.matiasnl.hakiosk.data.display.DisplayPreferences
import com.matiasnl.hakiosk.data.display.InMemoryDisplayPreferencesStore
import com.matiasnl.hakiosk.data.ha.HaServerConfig
import com.matiasnl.hakiosk.data.ha.fake.InMemoryHaConfigStore
import java.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/** Layout store whose flow only emits once [release] is called, like DataStore's first real read. */
private class GatedDashboardLayoutStore(private val storedLayout: DashboardLayout) : DashboardLayoutStore {
    private val gate = CompletableDeferred<Unit>()

    override val stored: Flow<StoredDashboardLayout> = flow {
        gate.await()
        emit(StoredDashboardLayout(storedLayout))
    }

    override suspend fun update(transform: (DashboardLayout) -> DashboardLayout) = Unit

    fun release() {
        gate.complete(Unit)
    }
}

class ConfigBackupRepositoryTest {

    private val storedLayout = DashboardLayout(
        views = listOf(
            DashboardView(
                id = "v1",
                name = "Principal",
                tiles = listOf(
                    DashboardTile("t1", TileContent.Entity("light.kitchen", "Cocina")),
                    DashboardTile("t2", TileContent.Entity("camera.door")),
                ),
            ),
        ),
    )

    private val storedMqtt = MqttConfig(
        host = "192.168.1.10",
        port = 1883,
        username = "kiosk",
        password = "broker-secret",
        useTls = false,
        deviceName = "Tablet cocina",
    )

    private val appVersion = ConfigBackupAppVersion("1.0.1", 10001)

    private fun repository(
        haConfigStore: InMemoryHaConfigStore = InMemoryHaConfigStore(HaServerConfig("https://ha.local:8123", "ha-token")),
        layoutStore: DashboardLayoutStore = InMemoryDashboardLayoutStore(storedLayout),
        viewPreferencesStore: InMemoryDashboardViewPreferencesStore =
            InMemoryDashboardViewPreferencesStore(DashboardViewPreferences(lastViewId = "v1", inactivityReturnMinutes = 5)),
        displayStore: InMemoryDisplayPreferencesStore = InMemoryDisplayPreferencesStore(
            DisplayPreferences(brightnessPercent = 80, screenOffTimeoutMinutes = 10, cameraCloseAfterSeconds = 30),
        ),
        mqttConfigStore: InMemoryMqttConfigStore = InMemoryMqttConfigStore(storedMqtt),
    ) = ConfigBackupRepository(
        haConfigStore = haConfigStore,
        dashboardLayoutStore = layoutStore,
        viewPreferencesStore = viewPreferencesStore,
        displayPreferencesStore = displayStore,
        mqttConfigStore = mqttConfigStore,
        appVersion = appVersion,
        clock = { Instant.parse("2026-09-17T12:00:00Z") },
    )

    @Test
    fun `read gathers what every store holds`() = runTest {
        val backup = repository().read()

        assertEquals(appVersion, backup.app)
        assertEquals("2026-09-17T12:00:00Z", backup.exportedAt)
        assertEquals("https://ha.local:8123", backup.haBaseUrl)
        assertEquals(storedLayout, backup.layout)
        assertEquals(DashboardViewPreferences(lastViewId = "v1", inactivityReturnMinutes = 5), backup.dashboardPreferences)
        assertEquals(80, backup.display.brightnessPercent)
        assertEquals(MqttBackup("192.168.1.10", 1883, "kiosk", false, "Tablet cocina"), backup.mqtt)
    }

    @Test
    fun `an exported file carries neither the token nor the broker password`() = runTest {
        val json = ConfigBackupJsonMapper.encode(repository().read())

        assertFalse(json.contains("ha-token"))
        assertFalse(json.contains("broker-secret"))
    }

    @Test
    fun `read waits for the stored layout instead of exporting a placeholder`() = runTest {
        val layoutStore = GatedDashboardLayoutStore(storedLayout)
        val repository = repository(layoutStore = layoutStore)

        val export = async { repository.read() }
        runCurrent()
        assertFalse(export.isCompleted)

        layoutStore.release()
        assertEquals(storedLayout, export.await().layout)
    }

    @Test
    fun `read reports no broker when remote control was never configured`() = runTest {
        val backup = repository(mqttConfigStore = InMemoryMqttConfigStore()).read()

        assertNull(backup.mqtt)
    }

    @Test
    fun `apply replaces the layout and the preferences`() = runTest {
        val layoutStore = InMemoryDashboardLayoutStore()
        val viewPreferencesStore = InMemoryDashboardViewPreferencesStore()
        val displayStore = InMemoryDisplayPreferencesStore()
        val repository = repository(
            layoutStore = layoutStore,
            viewPreferencesStore = viewPreferencesStore,
            displayStore = displayStore,
        )

        repository.apply(
            ConfigBackup(
                app = appVersion,
                exportedAt = "2026-09-17T12:00:00Z",
                haBaseUrl = null,
                layout = storedLayout,
                dashboardPreferences = DashboardViewPreferences(lastViewId = "v1", inactivityReturnMinutes = 15),
                display = DisplayPreferences(brightnessPercent = 40, screenOffTimeoutMinutes = 3, cameraCloseAfterSeconds = 90),
                mqtt = null,
            ),
        )

        assertEquals(storedLayout, layoutStore.layout.value)
        assertEquals(DashboardViewPreferences("v1", 15), viewPreferencesStore.preferences.value)
        assertEquals(DisplayPreferences(40, 3, 90), displayStore.preferences.value)
    }

    @Test
    fun `apply restores the base url without touching the stored token`() = runTest {
        val haConfigStore = InMemoryHaConfigStore(HaServerConfig("https://old.local", "ha-token"))
        val repository = repository(haConfigStore = haConfigStore)

        repository.apply(repository().read().copy(haBaseUrl = "https://imported.local:8443"))

        assertEquals("https://imported.local:8443", haConfigStore.baseUrl.first())
        assertEquals(HaServerConfig("https://imported.local:8443", "ha-token"), haConfigStore.config.first())
    }

    @Test
    fun `apply keeps the broker password already stored, which no file can carry`() = runTest {
        val mqttConfigStore = InMemoryMqttConfigStore(storedMqtt)
        val repository = repository(mqttConfigStore = mqttConfigStore)
        val backup = repository().read()

        repository.apply(backup.copy(mqtt = backup.mqtt?.copy(host = "10.0.0.5", deviceName = "Tablet living")))

        val saved = mqttConfigStore.config.first()
        assertEquals("10.0.0.5", saved?.host)
        assertEquals("Tablet living", saved?.deviceName)
        assertEquals("broker-secret", saved?.password)
    }

    @Test
    fun `apply leaves the broker alone when the backup has no broker section`() = runTest {
        val mqttConfigStore = InMemoryMqttConfigStore(storedMqtt)
        val repository = repository(mqttConfigStore = mqttConfigStore)

        repository.apply(repository().read().copy(mqtt = null))

        assertEquals(storedMqtt, mqttConfigStore.config.first())
    }

    @Test
    fun `apply keeps the current brightness when the backup follows the system one`() = runTest {
        val displayStore = InMemoryDisplayPreferencesStore(DisplayPreferences(brightnessPercent = 55))
        val repository = repository(displayStore = displayStore)

        repository.apply(repository().read().copy(display = DisplayPreferences(brightnessPercent = null)))

        assertEquals(55, displayStore.preferences.value.brightnessPercent)
    }

    @Test
    fun `a file exported from one tablet restores the same configuration on another`() = runTest {
        val exported = ConfigBackupJsonMapper.encode(repository().read())

        // A freshly installed tablet: empty stores, no token, no broker password.
        val freshHa = InMemoryHaConfigStore()
        val freshLayout = InMemoryDashboardLayoutStore()
        val freshViewPreferences = InMemoryDashboardViewPreferencesStore()
        val freshDisplay = InMemoryDisplayPreferencesStore()
        val freshMqtt = InMemoryMqttConfigStore()
        val fresh = repository(freshHa, freshLayout, freshViewPreferences, freshDisplay, freshMqtt)

        val decoded = ConfigBackupJsonMapper.decode(exported)
        check(decoded is ConfigBackupDecodeResult.Success) { "expected Success but was $decoded" }
        fresh.apply(decoded.backup)

        assertEquals(decoded.backup, fresh.read())
        assertEquals(storedLayout, freshLayout.layout.value)
        // The two secrets the file can't carry: both still missing, as warned in the UI.
        assertNull(freshHa.config.first())
        assertNull(freshMqtt.config.first()?.password)
    }
}
