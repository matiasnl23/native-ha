package com.matiasnl.hakiosk.data.config

import com.matiasnl.hakiosk.data.dashboard.DashboardLayoutStore
import com.matiasnl.hakiosk.data.dashboard.DashboardViewPreferencesStore
import com.matiasnl.hakiosk.data.device.MqttConfig
import com.matiasnl.hakiosk.data.device.MqttConfigStore
import com.matiasnl.hakiosk.data.display.DisplayPreferencesStore
import com.matiasnl.hakiosk.data.ha.HaConfigStore
import java.time.Instant
import kotlinx.coroutines.flow.first

/**
 * Gathers a [ConfigBackup] from the stores and writes one back into them. Knows nothing about files
 * or JSON (see [ConfigBackupJsonMapper]) — only about what a backup is made of.
 */
class ConfigBackupRepository(
    private val haConfigStore: HaConfigStore,
    private val dashboardLayoutStore: DashboardLayoutStore,
    private val viewPreferencesStore: DashboardViewPreferencesStore,
    private val displayPreferencesStore: DisplayPreferencesStore,
    private val mqttConfigStore: MqttConfigStore,
    private val appVersion: ConfigBackupAppVersion,
    private val clock: () -> Instant = Instant::now,
) {

    /**
     * Reads what is actually persisted right now.
     *
     * Every value comes from `first()` on the store's own flow, never from a snapshot of a
     * [kotlinx.coroutines.flow.StateFlow] that might still be holding a placeholder: it suspends
     * until the stored value has really been read, so an export can't write an empty dashboard over
     * a perfectly good one on the way to the file. Same rule as
     * [com.matiasnl.hakiosk.ui.dashboard.DashboardViewModel.enterEditMode], which refuses to act
     * until the real layout has loaded.
     */
    suspend fun read(): ConfigBackup {
        val layout = dashboardLayoutStore.layout.first()
        val dashboardPreferences = viewPreferencesStore.preferences.first()
        val display = displayPreferencesStore.preferences.first()
        val mqtt = mqttConfigStore.config.first()
        val baseUrl = haConfigStore.baseUrl.first()

        return ConfigBackup(
            app = appVersion,
            exportedAt = clock().toString(),
            haBaseUrl = baseUrl,
            layout = layout,
            dashboardPreferences = dashboardPreferences,
            display = display,
            // Rebuilt field by field into a type with no password, rather than passed along: the
            // broker password is encrypted with a Keystore key and must never reach the file.
            mqtt = mqtt?.let {
                MqttBackup(
                    host = it.host,
                    port = it.port,
                    username = it.username,
                    useTls = it.useTls,
                    deviceName = it.deviceName,
                )
            },
        )
    }

    /**
     * Restores [backup], overwriting what is stored.
     *
     * Only ever called with a backup that [ConfigBackupJsonMapper.decode] already accepted whole and
     * the user then confirmed, which is what keeps a half-done import from happening: every way a
     * file can be wrong is decided before the first write. The stores own separate DataStore files
     * and there is no transaction spanning them, so the dashboard layout — the part that would take
     * hours to rebuild by hand — is written first.
     *
     * Two things are deliberately left alone: the Home Assistant token (only the base URL is
     * written, see [HaConfigStore.saveBaseUrl]) and the broker password, which is kept from the
     * stored config so re-importing on a working tablet doesn't force the user to retype it.
     */
    suspend fun apply(backup: ConfigBackup) {
        dashboardLayoutStore.update { backup.layout }

        backup.dashboardPreferences.lastViewId?.let { viewPreferencesStore.setLastViewId(it) }
        viewPreferencesStore.setInactivityReturnMinutes(backup.dashboardPreferences.inactivityReturnMinutes)

        // A backup with no brightness override means "follow the system brightness", which is also
        // what the store means by having no value: leave whatever is there rather than force a number.
        backup.display.brightnessPercent?.let { displayPreferencesStore.setBrightnessPercent(it) }
        displayPreferencesStore.setScreenOffTimeoutMinutes(backup.display.screenOffTimeoutMinutes)
        displayPreferencesStore.setCameraCloseAfterSeconds(backup.display.cameraCloseAfterSeconds)

        backup.mqtt?.let { mqtt ->
            val storedPassword = mqttConfigStore.config.first()?.password
            mqttConfigStore.save(
                MqttConfig(
                    host = mqtt.host,
                    port = mqtt.port,
                    username = mqtt.username,
                    password = storedPassword,
                    useTls = mqtt.useTls,
                    deviceName = mqtt.deviceName,
                ),
            )
        }

        backup.haBaseUrl?.let { haConfigStore.saveBaseUrl(it) }
    }
}
