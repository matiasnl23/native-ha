package com.matiasnl.hakiosk.data.config

import com.matiasnl.hakiosk.data.dashboard.DashboardGrid
import com.matiasnl.hakiosk.data.dashboard.DashboardLayout
import com.matiasnl.hakiosk.data.dashboard.DashboardTile
import com.matiasnl.hakiosk.data.dashboard.DashboardView
import com.matiasnl.hakiosk.data.dashboard.DashboardViewPreferences
import com.matiasnl.hakiosk.data.dashboard.TileContent
import com.matiasnl.hakiosk.data.display.DisplayPreferences
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfigBackupJsonMapperTest {

    private val layout = DashboardLayout(
        views = listOf(
            DashboardView(
                id = "v1",
                name = "Principal",
                grid = DashboardGrid(columns = 5, rows = 3),
                tiles = listOf(
                    DashboardTile("t1", TileContent.Entity("light.kitchen", "Cocina")),
                    DashboardTile("t2", TileContent.Spacer),
                ),
            ),
            DashboardView(id = "v2", name = "Cámaras", tiles = listOf(DashboardTile("t3", TileContent.Entity("camera.door")))),
        ),
    )

    private val backup = ConfigBackup(
        app = ConfigBackupAppVersion("1.0.1", 10001),
        exportedAt = "2026-09-17T12:00:00Z",
        haBaseUrl = "https://ha.example.com:8123",
        layout = layout,
        dashboardPreferences = DashboardViewPreferences(lastViewId = "v2", inactivityReturnMinutes = 5),
        display = DisplayPreferences(brightnessPercent = 80, screenOffTimeoutMinutes = 10, cameraCloseAfterSeconds = 30),
        mqtt = MqttBackup(host = "192.168.1.10", port = 8883, username = "kiosk", useTls = true, deviceName = "Tablet cocina"),
    )

    private fun decodeSuccess(text: String): ConfigBackup {
        val result = ConfigBackupJsonMapper.decode(text)
        check(result is ConfigBackupDecodeResult.Success) { "expected Success but was $result" }
        return result.backup
    }

    @Test
    fun `encode then decode round-trips the whole configuration`() {
        val decoded = decodeSuccess(ConfigBackupJsonMapper.encode(backup))

        assertEquals(backup, decoded)
    }

    @Test
    fun `the exported file carries the format marker and the app version`() {
        val json = ConfigBackupJsonMapper.encode(backup)

        assertTrue(json.contains("\"format\": \"hakiosk-config\""))
        assertTrue(json.contains("\"version\": 1"))
        assertTrue(json.contains("\"versionName\": \"1.0.1\""))
        assertTrue(json.contains("\"versionCode\": 10001"))
    }

    @Test
    fun `the exported file never contains the token or the broker password`() {
        val json = ConfigBackupJsonMapper.encode(backup)

        assertFalse(json.contains("token", ignoreCase = true))
        assertFalse(json.contains("password", ignoreCase = true))
        assertFalse(json.contains("deviceId"))
    }

    @Test
    fun `a file that is not json at all is corrupt`() {
        assertEquals(ConfigBackupDecodeResult.Corrupt, ConfigBackupJsonMapper.decode("{not json"))
        assertEquals(ConfigBackupDecodeResult.Corrupt, ConfigBackupJsonMapper.decode(""))
        // Valid JSON, but not an object.
        assertEquals(ConfigBackupDecodeResult.Corrupt, ConfigBackupJsonMapper.decode("[1,2,3]"))
    }

    @Test
    fun `json from another app is rejected as not a backup`() {
        assertEquals(ConfigBackupDecodeResult.NotABackup, ConfigBackupJsonMapper.decode("""{"some":"other file"}"""))
        assertEquals(
            ConfigBackupDecodeResult.NotABackup,
            ConfigBackupJsonMapper.decode("""{"format":"someone-elses-config","version":1}"""),
        )
    }

    @Test
    fun `a bare dashboard layout file is rejected as not a backup`() {
        // The layout envelope has a `version` too: the format marker is what tells them apart.
        val layoutOnly = """{"version":1,"views":[{"id":"v1","name":"Principal","tiles":[]}]}"""

        assertEquals(ConfigBackupDecodeResult.NotABackup, ConfigBackupJsonMapper.decode(layoutOnly))
    }

    @Test
    fun `a backup from a future version is refused instead of guessed at`() {
        // replaceFirst: the nested dashboard envelope carries a version of its own, and only the
        // backup envelope's (written first) is the one this test is about.
        val fromTheFuture = ConfigBackupJsonMapper.encode(backup).replaceFirst("\"version\": 1,", "\"version\": 2,")

        assertEquals(ConfigBackupDecodeResult.FutureVersion(2), ConfigBackupJsonMapper.decode(fromTheFuture))
    }

    @Test
    fun `a missing or unreadable version is corrupt`() {
        assertEquals(
            ConfigBackupDecodeResult.Corrupt,
            ConfigBackupJsonMapper.decode("""{"format":"hakiosk-config"}"""),
        )
        assertEquals(
            ConfigBackupDecodeResult.Corrupt,
            ConfigBackupJsonMapper.decode("""{"format":"hakiosk-config","version":"one"}"""),
        )
        assertEquals(
            ConfigBackupDecodeResult.Corrupt,
            ConfigBackupJsonMapper.decode("""{"format":"hakiosk-config","version":0}"""),
        )
    }

    @Test
    fun `a backup without a dashboard section is corrupt`() {
        assertEquals(
            ConfigBackupDecodeResult.Corrupt,
            ConfigBackupJsonMapper.decode("""{"format":"hakiosk-config","version":1}"""),
        )
    }

    @Test
    fun `an unreadable dashboard is refused rather than imported as an empty one`() {
        val truncated = """{"format":"hakiosk-config","version":1,"dashboard":{"version":1,"views":"oops"}}"""
        val noViews = """{"format":"hakiosk-config","version":1,"dashboard":{"version":1,"views":[]}}"""
        val futureLayout = """{"format":"hakiosk-config","version":1,"dashboard":{"version":99,"views":[{"id":"v","name":"V"}]}}"""

        assertEquals(ConfigBackupDecodeResult.Corrupt, ConfigBackupJsonMapper.decode(truncated))
        assertEquals(ConfigBackupDecodeResult.Corrupt, ConfigBackupJsonMapper.decode(noViews))
        assertEquals(ConfigBackupDecodeResult.Corrupt, ConfigBackupJsonMapper.decode(futureLayout))
    }

    @Test
    fun `a broken section rejects the whole file instead of importing the rest`() {
        val badPort = ConfigBackupJsonMapper.encode(backup).replace("\"port\": 8883", "\"port\": 70000")
        val badUrl = ConfigBackupJsonMapper.encode(backup).replace("https://ha.example.com:8123", "ha.example.com")
        val badType = ConfigBackupJsonMapper.encode(backup).replace("\"useTls\": true", "\"useTls\": \"yes\"")

        assertEquals(ConfigBackupDecodeResult.Corrupt, ConfigBackupJsonMapper.decode(badPort))
        assertEquals(ConfigBackupDecodeResult.Corrupt, ConfigBackupJsonMapper.decode(badUrl))
        assertEquals(ConfigBackupDecodeResult.Corrupt, ConfigBackupJsonMapper.decode(badType))
    }

    @Test
    fun `a blank broker host or device name rejects the file`() {
        val blankHost = ConfigBackupJsonMapper.encode(backup).replace("\"host\": \"192.168.1.10\"", "\"host\": \"  \"")
        val blankName = ConfigBackupJsonMapper.encode(backup).replace("\"deviceName\": \"Tablet cocina\"", "\"deviceName\": \"\"")

        assertEquals(ConfigBackupDecodeResult.Corrupt, ConfigBackupJsonMapper.decode(blankHost))
        assertEquals(ConfigBackupDecodeResult.Corrupt, ConfigBackupJsonMapper.decode(blankName))
    }

    @Test
    fun `optional sections may be absent and restore nothing`() {
        val minimal = """
            {"format":"hakiosk-config","version":1,
             "dashboard":{"version":1,"views":[{"id":"v1","name":"Principal","tiles":[]}]}}
        """.trimIndent()

        val decoded = decodeSuccess(minimal)

        assertNull(decoded.haBaseUrl)
        assertNull(decoded.mqtt)
        assertEquals(listOf("v1"), decoded.layout.views.map { it.id })
        assertEquals(DashboardViewPreferences(), decoded.dashboardPreferences)
        assertEquals(DisplayPreferences(), decoded.display)
        assertEquals(ConfigBackupJsonMapper.UNKNOWN_APP_VERSION, decoded.app.versionName)
    }

    @Test
    fun `values out of range are clamped instead of rejecting the file`() {
        val json = ConfigBackupJsonMapper.encode(backup)
            .replace("\"brightnessPercent\": 80", "\"brightnessPercent\": 250")
            .replace("\"screenOffTimeoutMinutes\": 10", "\"screenOffTimeoutMinutes\": -4")
            .replace("\"inactivityReturnMinutes\": 5", "\"inactivityReturnMinutes\": 7")

        val decoded = decodeSuccess(json)

        assertEquals(100, decoded.display.brightnessPercent)
        assertEquals(0, decoded.display.screenOffTimeoutMinutes)
        // 7 isn't one of the offered choices, so it means "disabled", as the store itself decides.
        assertEquals(0, decoded.dashboardPreferences.inactivityReturnMinutes)
    }

    @Test
    fun `unknown keys written by a newer app of the same version are ignored`() {
        val json = ConfigBackupJsonMapper.encode(backup)
            .replace("\"exportedAt\"", "\"somethingNew\": {\"a\": 1}, \"exportedAt\"")

        assertEquals(backup, decodeSuccess(json))
    }

    @Test
    fun `a backup with no brightness override keeps following the system brightness`() {
        val noBrightness = backup.copy(display = DisplayPreferences(brightnessPercent = null))

        val decoded = decodeSuccess(ConfigBackupJsonMapper.encode(noBrightness))

        assertNull(decoded.display.brightnessPercent)
        assertFalse(ConfigBackupJsonMapper.encode(noBrightness).contains("brightnessPercent"))
    }

    @Test
    fun `the summary counts what is about to be overwritten`() {
        val summary = backup.summary()

        assertEquals(2, summary.viewCount)
        assertEquals(3, summary.tileCount)
        assertTrue(summary.hasBroker)
        assertEquals("1.0.1", summary.appVersionName)
    }

    @Test
    fun `the suggested file name carries the date`() {
        assertEquals("hakiosk-config-2026-09-17.json", suggestedBackupFileName(LocalDate.of(2026, 9, 17)))
    }
}
