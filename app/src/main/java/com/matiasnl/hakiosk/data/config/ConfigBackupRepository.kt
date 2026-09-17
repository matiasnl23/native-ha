package com.matiasnl.hakiosk.data.config

import com.matiasnl.hakiosk.data.dashboard.DashboardLayoutStore
import com.matiasnl.hakiosk.data.dashboard.DashboardViewPreferencesStore
import com.matiasnl.hakiosk.data.device.MqttConfig
import com.matiasnl.hakiosk.data.device.MqttConfigStore
import com.matiasnl.hakiosk.data.display.DisplayPreferencesStore
import com.matiasnl.hakiosk.data.ha.HaConfigStore
import java.time.Instant
import kotlinx.coroutines.flow.first

/** What [ConfigBackupRepository.read] found. */
sealed interface GatheredConfigBackup {
    data class Available(val backup: ConfigBackup) : GatheredConfigBackup

    /**
     * The persisted dashboard layout couldn't be decoded, so what the app shows is a default
     * stand-in (see [com.matiasnl.hakiosk.data.dashboard.StoredDashboardLayout]). Exporting it would
     * save an empty dashboard as if it were the user's, and that file would later be restored over
     * the real one after the reinstall — precisely the loss this feature exists to prevent.
     */
    data object UnreadableLayout : GatheredConfigBackup
}

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
     * Reads what is actually persisted right now, or refuses when the dashboard can't be trusted.
     *
     * Two different placeholders have to be kept out of the file, and each needs its own guard:
     * - *Not loaded yet*: every value comes from `first()` on the store's own flow, never from a
     *   snapshot of a [kotlinx.coroutines.flow.StateFlow] that might still hold its initial value.
     *   It suspends until the stored value has really been read. Same rule as
     *   [com.matiasnl.hakiosk.ui.dashboard.DashboardViewModel.enterEditMode].
     * - *Loaded, but not the user's data*: a layout that couldn't be decoded still arrives as a
     *   perfectly valid default [DashboardLayout]. `first()` can't tell that apart, so
     *   [com.matiasnl.hakiosk.data.dashboard.StoredDashboardLayout.isReadable] does, and this
     *   returns [GatheredConfigBackup.UnreadableLayout] instead of exporting the stand-in.
     */
    suspend fun read(): GatheredConfigBackup {
        val stored = dashboardLayoutStore.stored.first()
        if (!stored.isReadable) return GatheredConfigBackup.UnreadableLayout

        val dashboardPreferences = viewPreferencesStore.preferences.first()
        val display = displayPreferencesStore.preferences.first()
        val mqtt = mqttConfigStore.config.first()
        val baseUrl = haConfigStore.baseUrl.first()

        return GatheredConfigBackup.Available(
            ConfigBackup(
                app = appVersion,
                exportedAt = clock().toString(),
                haBaseUrl = baseUrl,
                layout = stored.layout,
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
            ),
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
