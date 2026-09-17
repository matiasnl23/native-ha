package com.matiasnl.hakiosk.data.config

import com.matiasnl.hakiosk.data.dashboard.DashboardLayout
import com.matiasnl.hakiosk.data.dashboard.DashboardViewPreferences
import com.matiasnl.hakiosk.data.display.DisplayPreferences
import java.time.LocalDate

/**
 * A whole exported configuration, already decoded and validated.
 *
 * What it deliberately leaves out — see `docs/RELEASE-OTA.md`:
 * - The Home Assistant token and the broker password: both are encrypted with an Android Keystore
 *   key that never leaves the device, so a copy would be useless anywhere else. They are pasted
 *   again by hand after importing.
 * - The MQTT `deviceId`: it must be unique per install, so a restored tablet registers itself in
 *   Home Assistant as a new device instead of fighting the old one for the same topics.
 */
data class ConfigBackup(
    val app: ConfigBackupAppVersion,
    /** When the file was written, as an ISO-8601 instant in UTC. Informational only. */
    val exportedAt: String,
    /** Home Assistant base URL, normalized; null when the exporting tablet had none stored. */
    val haBaseUrl: String?,
    val layout: DashboardLayout,
    val dashboardPreferences: DashboardViewPreferences,
    val display: DisplayPreferences,
    /** Broker settings, or null when remote control wasn't configured. Never carries the password. */
    val mqtt: MqttBackup?,
) {
    /** The few numbers the confirmation dialog shows before anything is overwritten. */
    fun summary(): ConfigBackupSummary = ConfigBackupSummary(
        viewCount = layout.views.size,
        tileCount = layout.views.sumOf { it.tiles.size },
        hasBroker = mqtt != null,
        appVersionName = app.versionName,
        exportedAt = exportedAt,
    )
}

/** Version of the app that wrote a backup, so a future version can tell where a file came from. */
data class ConfigBackupAppVersion(
    val versionName: String,
    val versionCode: Long,
)

/**
 * The broker settings a backup carries. Deliberately not [com.matiasnl.hakiosk.data.device.MqttConfig]:
 * without a password field in the type, no export can write the password even by mistake.
 */
data class MqttBackup(
    val host: String,
    val port: Int,
    val username: String?,
    val useTls: Boolean,
    val deviceName: String,
)

/** What an import is about to restore, shown for confirmation while nothing has been written yet. */
data class ConfigBackupSummary(
    val viewCount: Int,
    val tileCount: Int,
    val hasBroker: Boolean,
    val appVersionName: String,
    val exportedAt: String,
)

/** Valid MQTT port range, shared by the broker settings screen's own validation. */
internal val BACKUP_PORT_RANGE = 1..65535

/** Default name offered by the system file picker, e.g. `hakiosk-config-2026-09-17.json`. */
fun suggestedBackupFileName(date: LocalDate): String = "hakiosk-config-$date.json"
