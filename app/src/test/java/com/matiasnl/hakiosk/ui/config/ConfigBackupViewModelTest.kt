package com.matiasnl.hakiosk.ui.config

import com.matiasnl.hakiosk.data.config.ConfigBackup
import com.matiasnl.hakiosk.data.config.ConfigBackupAppVersion
import com.matiasnl.hakiosk.data.config.ConfigBackupFiles
import com.matiasnl.hakiosk.data.config.ConfigBackupJsonMapper
import com.matiasnl.hakiosk.data.config.ConfigBackupReadResult
import com.matiasnl.hakiosk.data.config.ConfigBackupRepository
import com.matiasnl.hakiosk.data.config.GatheredConfigBackup
import com.matiasnl.hakiosk.data.config.MqttBackup
import com.matiasnl.hakiosk.data.dashboard.DashboardLayout
import com.matiasnl.hakiosk.data.dashboard.DashboardTile
import com.matiasnl.hakiosk.data.dashboard.DashboardView
import com.matiasnl.hakiosk.data.dashboard.DashboardViewPreferences
import com.matiasnl.hakiosk.data.dashboard.InMemoryDashboardLayoutStore
import com.matiasnl.hakiosk.data.dashboard.InMemoryDashboardViewPreferencesStore
import com.matiasnl.hakiosk.data.dashboard.TileContent
import com.matiasnl.hakiosk.data.device.fake.InMemoryMqttConfigStore
import com.matiasnl.hakiosk.data.display.DisplayPreferences
import com.matiasnl.hakiosk.data.display.InMemoryDisplayPreferencesStore
import com.matiasnl.hakiosk.data.ha.HaServerConfig
import com.matiasnl.hakiosk.data.ha.fake.InMemoryHaConfigStore
import com.matiasnl.hakiosk.ui.MainDispatcherRule
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

private class FakeConfigBackupFiles(
    var readResult: ConfigBackupReadResult = ConfigBackupReadResult.Unreadable,
    var writeSucceeds: Boolean = true,
) : ConfigBackupFiles {
    var written: String? = null
        private set
    var writtenUri: String? = null
        private set

    override suspend fun read(uri: String): ConfigBackupReadResult = readResult

    override suspend fun write(uri: String, content: String): Boolean {
        if (!writeSucceeds) return false
        writtenUri = uri
        written = content
        return true
    }
}

class ConfigBackupViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val storedLayout = DashboardLayout(
        views = listOf(
            DashboardView(
                id = "v1",
                name = "Principal",
                tiles = listOf(DashboardTile("t1", TileContent.Entity("light.kitchen"))),
            ),
        ),
    )

    /** A different layout, so applying the file is visible in the store. */
    private val fileLayout = DashboardLayout(
        views = listOf(
            DashboardView(id = "imported", name = "Importada", tiles = listOf(DashboardTile("ti", TileContent.Spacer))),
            DashboardView(id = "imported2", name = "Segunda"),
        ),
    )

    private val layoutStore = InMemoryDashboardLayoutStore(storedLayout)
    private val haConfigStore = InMemoryHaConfigStore(HaServerConfig("https://ha.local:8123", "ha-token"))
    private val mqttConfigStore = InMemoryMqttConfigStore()

    private val repository = ConfigBackupRepository(
        haConfigStore = haConfigStore,
        dashboardLayoutStore = layoutStore,
        viewPreferencesStore = InMemoryDashboardViewPreferencesStore(),
        displayPreferencesStore = InMemoryDisplayPreferencesStore(),
        mqttConfigStore = mqttConfigStore,
        appVersion = ConfigBackupAppVersion("1.0.1", 10001),
        clock = { Instant.parse("2026-09-17T12:00:00Z") },
    )

    private val files = FakeConfigBackupFiles()

    private fun viewModel() = ConfigBackupViewModel(repository, files, today = { LocalDate.of(2026, 9, 17) })

    private fun fileContent(
        layout: DashboardLayout = fileLayout,
        mqtt: MqttBackup? = MqttBackup("broker.lan", 1883, null, false, "Tablet cocina"),
    ): String = ConfigBackupJsonMapper.encode(
        ConfigBackup(
            app = ConfigBackupAppVersion("1.0.0", 10000),
            exportedAt = "2026-09-01T10:00:00Z",
            haBaseUrl = "https://imported.local:8123",
            layout = layout,
            dashboardPreferences = DashboardViewPreferences(lastViewId = "imported", inactivityReturnMinutes = 5),
            display = DisplayPreferences(brightnessPercent = 60),
            mqtt = mqtt,
        ),
    )

    private fun importing(content: String): ConfigBackupViewModel {
        files.readResult = ConfigBackupReadResult.Success(content)
        return viewModel().also { it.onImportFileChosen("content://chosen") }
    }

    @Test
    fun `the suggested file name carries today's date`() {
        assertEquals("hakiosk-config-2026-09-17.json", viewModel().suggestedFileName())
    }

    @Test
    fun `export writes the whole configuration to the chosen file`() = runTest {
        val viewModel = viewModel()

        viewModel.export("content://new-file")

        assertEquals(ConfigBackupStatus.ExportDone, viewModel.uiState.value.status)
        assertEquals("content://new-file", files.writtenUri)
        val written = requireNotNull(files.written)
        val gathered = repository.read()
        check(gathered is GatheredConfigBackup.Available)
        assertEquals(ConfigBackupJsonMapper.encode(gathered.backup), written)
    }

    @Test
    fun `export refuses a dashboard that could not be read, instead of saving an empty one`() = runTest {
        val unreadableRepository = ConfigBackupRepository(
            haConfigStore = haConfigStore,
            // Persisted data that failed to decode: the store hands out a default stand-in.
            dashboardLayoutStore = InMemoryDashboardLayoutStore(storedLayout, isReadable = false),
            viewPreferencesStore = InMemoryDashboardViewPreferencesStore(),
            displayPreferencesStore = InMemoryDisplayPreferencesStore(),
            mqttConfigStore = mqttConfigStore,
            appVersion = ConfigBackupAppVersion("1.0.1", 10001),
        )
        val viewModel = ConfigBackupViewModel(unreadableRepository, files, today = { LocalDate.of(2026, 9, 17) })

        viewModel.export("content://new-file")

        assertEquals(
            ConfigBackupStatus.ExportFailed(ConfigExportFailure.UnreadableLayout),
            viewModel.uiState.value.status,
        )
        assertNull(files.written)
    }

    @Test
    fun `a file that cannot be written is reported`() = runTest {
        files.writeSucceeds = false
        val viewModel = viewModel()

        viewModel.export("content://new-file")

        assertEquals(
            ConfigBackupStatus.ExportFailed(ConfigExportFailure.NotWritten),
            viewModel.uiState.value.status,
        )
        assertNull(files.written)
    }

    @Test
    fun `choosing a valid file only asks for confirmation and writes nothing yet`() = runTest {
        val viewModel = importing(fileContent())

        val summary = viewModel.uiState.value.pendingImport
        assertNotNull(summary)
        assertEquals(2, summary?.viewCount)
        assertEquals(1, summary?.tileCount)
        assertEquals("1.0.0", summary?.appVersionName)
        assertEquals(ConfigBackupStatus.Idle, viewModel.uiState.value.status)
        // Nothing has been touched until the user confirms.
        assertEquals(storedLayout, layoutStore.layout.value)
    }

    @Test
    fun `confirming applies the file`() = runTest {
        val viewModel = importing(fileContent())

        viewModel.confirmImport()

        assertEquals(ConfigBackupStatus.ImportDone, viewModel.uiState.value.status)
        assertNull(viewModel.uiState.value.pendingImport)
        assertEquals(fileLayout, layoutStore.layout.value)
        assertEquals("https://imported.local:8123", haConfigStore.baseUrl.value)
        assertEquals("broker.lan", mqttConfigStore.config.value?.host)
        // The token was never in the file, and importing doesn't drop the one already stored.
        assertEquals("ha-token", haConfigStore.config.value?.token)
    }

    @Test
    fun `cancelling discards the file without writing any of it`() = runTest {
        val viewModel = importing(fileContent())

        viewModel.cancelImport()

        assertNull(viewModel.uiState.value.pendingImport)
        assertEquals(ConfigBackupStatus.Idle, viewModel.uiState.value.status)
        assertEquals(storedLayout, layoutStore.layout.value)
        // Confirming afterwards must not resurrect the discarded file.
        viewModel.confirmImport()
        assertEquals(storedLayout, layoutStore.layout.value)
    }

    @Test
    fun `a file from another app is refused without touching anything`() = runTest {
        val viewModel = importing("""{"some":"other file"}""")

        assertEquals(
            ConfigBackupStatus.ImportFailed(ConfigImportFailure.NotABackup),
            viewModel.uiState.value.status,
        )
        assertNull(viewModel.uiState.value.pendingImport)
        assertEquals(storedLayout, layoutStore.layout.value)
    }

    @Test
    fun `a corrupt file is refused without touching anything`() = runTest {
        val viewModel = importing("{not json at all")

        assertEquals(ConfigBackupStatus.ImportFailed(ConfigImportFailure.Corrupt), viewModel.uiState.value.status)
        assertEquals(storedLayout, layoutStore.layout.value)
    }

    @Test
    fun `a file whose dashboard is broken is refused instead of emptying the dashboard`() = runTest {
        val broken = fileContent().replace("\"views\"", "\"viewsBroken\"")

        val viewModel = importing(broken)

        assertEquals(ConfigBackupStatus.ImportFailed(ConfigImportFailure.Corrupt), viewModel.uiState.value.status)
        assertEquals(storedLayout, layoutStore.layout.value)
    }

    @Test
    fun `a file from a newer version is refused`() = runTest {
        val newer = fileContent().replace("\"version\": 1,", "\"version\": 7,")

        val viewModel = importing(newer)

        assertEquals(
            ConfigBackupStatus.ImportFailed(ConfigImportFailure.FutureVersion(7)),
            viewModel.uiState.value.status,
        )
        assertEquals(storedLayout, layoutStore.layout.value)
    }

    @Test
    fun `an unreadable or oversized file is refused`() = runTest {
        files.readResult = ConfigBackupReadResult.Unreadable
        val unreadable = viewModel().also { it.onImportFileChosen("content://gone") }
        assertEquals(
            ConfigBackupStatus.ImportFailed(ConfigImportFailure.Unreadable),
            unreadable.uiState.value.status,
        )

        files.readResult = ConfigBackupReadResult.TooLarge
        val tooLarge = viewModel().also { it.onImportFileChosen("content://huge") }
        assertEquals(ConfigBackupStatus.ImportFailed(ConfigImportFailure.TooLarge), tooLarge.uiState.value.status)

        assertEquals(storedLayout, layoutStore.layout.value)
    }
}
