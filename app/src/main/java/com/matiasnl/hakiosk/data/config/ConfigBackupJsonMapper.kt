package com.matiasnl.hakiosk.data.config

import com.matiasnl.hakiosk.data.dashboard.DashboardLayoutJsonMapper
import com.matiasnl.hakiosk.data.dashboard.DashboardViewPreferences
import com.matiasnl.hakiosk.data.dashboard.sanitizeInactivityMinutes
import com.matiasnl.hakiosk.data.display.DEFAULT_CAMERA_CLOSE_AFTER_SECONDS
import com.matiasnl.hakiosk.data.display.DEFAULT_SCREEN_OFF_TIMEOUT_MINUTES
import com.matiasnl.hakiosk.data.display.DisplayPreferences
import com.matiasnl.hakiosk.data.display.sanitizeBrightnessPercent
import com.matiasnl.hakiosk.data.display.sanitizeNonNegative
import com.matiasnl.hakiosk.data.ha.HaUrls
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement

/** Marks the file as ours. A JSON file without exactly this marker is never imported. */
const val CONFIG_BACKUP_FORMAT = "hakiosk-config"

/** Format version of the envelope. Bumped only when a future version can no longer read v1 as-is. */
const val CONFIG_BACKUP_VERSION = 1

@Serializable
private data class PersistedApp(val versionName: String, val versionCode: Long)

@Serializable
private data class PersistedHomeAssistant(val baseUrl: String)

@Serializable
private data class PersistedDashboardPreferences(
    val lastViewId: String? = null,
    val inactivityReturnMinutes: Int = 0,
)

/** Defaults match the app's own, so a field a hand-edited file leaves out means "app default". */
@Serializable
private data class PersistedDisplay(
    val brightnessPercent: Int? = null,
    val screenOffTimeoutMinutes: Int = DEFAULT_SCREEN_OFF_TIMEOUT_MINUTES,
    val cameraCloseAfterSeconds: Int = DEFAULT_CAMERA_CLOSE_AFTER_SECONDS,
)

/** No password field on purpose: see [MqttBackup]. */
@Serializable
private data class PersistedMqtt(
    val host: String,
    val port: Int,
    val username: String? = null,
    val useTls: Boolean = false,
    val deviceName: String,
)

/**
 * The file's envelope. [format] and [version] are what let a future version recognize the file and
 * decide whether it can read it, the same criterion as
 * [com.matiasnl.hakiosk.data.dashboard.DashboardLayoutJsonMapper]'s own envelope — which is nested
 * verbatim under [dashboard], so the layout keeps its own independent version.
 */
@Serializable
private data class PersistedBackup(
    val format: String = CONFIG_BACKUP_FORMAT,
    val version: Int = CONFIG_BACKUP_VERSION,
    val app: PersistedApp? = null,
    val exportedAt: String? = null,
    val homeAssistant: PersistedHomeAssistant? = null,
    val dashboard: JsonElement? = null,
    val dashboardPreferences: PersistedDashboardPreferences? = null,
    val display: PersistedDisplay? = null,
    val mqtt: PersistedMqtt? = null,
)

/** Why a chosen file can't be imported. Nothing is ever written for any of these. */
sealed interface ConfigBackupDecodeResult {
    data class Success(val backup: ConfigBackup) : ConfigBackupDecodeResult

    /** Valid JSON, but without our [CONFIG_BACKUP_FORMAT] marker: some other app's file. */
    data object NotABackup : ConfigBackupDecodeResult

    /** Ours, but written by a newer app version whose format this one can only guess at. */
    data class FutureVersion(val version: Int) : ConfigBackupDecodeResult

    /** Not JSON, or ours but structurally broken (truncated, hand-edited, missing the dashboard). */
    data object Corrupt : ConfigBackupDecodeResult
}

/**
 * Pure JSON <-> [ConfigBackup] mapping: the versioned envelope and all of the import validation.
 * Free of Android types so the rules that protect the user's dashboard can be unit-tested on the
 * plain JVM.
 *
 * The validation rule, applied whole-file: a section that is present but can't be read as what it
 * claims to be rejects the entire file ([ConfigBackupDecodeResult.Corrupt]) rather than importing
 * the rest, because a file that broken says nothing reliable about the rest of its content. Values
 * that are readable but out of range are clamped to the app's own limits instead, exactly as the
 * stores do with their own data. An absent optional section simply restores nothing.
 */
object ConfigBackupJsonMapper {
    // encodeDefaults so `format` and `version` are always written even though they equal their
    // defaults — without them the file couldn't be recognized. explicitNulls off keeps absent
    // optional values (no brightness override, no broker username) out of the file entirely.
    private val json = Json {
        prettyPrint = true
        encodeDefaults = true
        explicitNulls = false
        ignoreUnknownKeys = true
    }

    fun encode(backup: ConfigBackup): String {
        val persisted = PersistedBackup(
            app = PersistedApp(backup.app.versionName, backup.app.versionCode),
            exportedAt = backup.exportedAt,
            homeAssistant = backup.haBaseUrl?.let { PersistedHomeAssistant(it) },
            // Nested verbatim, so the dashboard keeps being serialized by the one mapper that owns
            // its format instead of a second copy of those rules drifting out of sync here.
            dashboard = json.parseToJsonElement(DashboardLayoutJsonMapper.encode(backup.layout)),
            dashboardPreferences = PersistedDashboardPreferences(
                lastViewId = backup.dashboardPreferences.lastViewId,
                inactivityReturnMinutes = backup.dashboardPreferences.inactivityReturnMinutes,
            ),
            display = PersistedDisplay(
                brightnessPercent = backup.display.brightnessPercent,
                screenOffTimeoutMinutes = backup.display.screenOffTimeoutMinutes,
                cameraCloseAfterSeconds = backup.display.cameraCloseAfterSeconds,
            ),
            mqtt = backup.mqtt?.let {
                PersistedMqtt(
                    host = it.host,
                    port = it.port,
                    username = it.username,
                    useTls = it.useTls,
                    deviceName = it.deviceName,
                )
            },
        )
        return json.encodeToString(persisted)
    }

    /** Reads and validates [text]. Never throws: every failure is one of [ConfigBackupDecodeResult]. */
    fun decode(text: String): ConfigBackupDecodeResult {
        val root = runCatching { json.parseToJsonElement(text) }.getOrNull() as? JsonObject
            ?: return ConfigBackupDecodeResult.Corrupt

        // The marker and the version are read before the rest so a file from another app, or from a
        // future version, is named as such instead of being reported as corrupt.
        val format = (root["format"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        if (format != CONFIG_BACKUP_FORMAT) return ConfigBackupDecodeResult.NotABackup
        val version = (root["version"] as? JsonPrimitive)?.content?.toIntOrNull()
            ?: return ConfigBackupDecodeResult.Corrupt
        if (version > CONFIG_BACKUP_VERSION) return ConfigBackupDecodeResult.FutureVersion(version)
        if (version < 1) return ConfigBackupDecodeResult.Corrupt

        val persisted = runCatching { json.decodeFromJsonElement<PersistedBackup>(root) }.getOrNull()
            ?: return ConfigBackupDecodeResult.Corrupt

        // Strict: an unreadable or empty dashboard must not silently become the default empty
        // layout, which is exactly how an import would wipe the work the backup exists to protect.
        val layout = persisted.dashboard
            ?.let { DashboardLayoutJsonMapper.decodeStrict(it.toString()) }
            ?: return ConfigBackupDecodeResult.Corrupt

        val baseUrl = persisted.homeAssistant?.let { section ->
            val normalized = HaUrls.normalizeBaseUrl(section.baseUrl)
            // An export always writes a normalized http(s) URL, so anything else is a broken file.
            if (!normalized.startsWith("http://") && !normalized.startsWith("https://")) {
                return ConfigBackupDecodeResult.Corrupt
            }
            normalized
        }

        val mqtt = persisted.mqtt?.let { section ->
            val host = section.host.trim()
            val deviceName = section.deviceName.trim()
            if (host.isEmpty() || deviceName.isEmpty() || section.port !in BACKUP_PORT_RANGE) {
                return ConfigBackupDecodeResult.Corrupt
            }
            MqttBackup(
                host = host,
                port = section.port,
                username = section.username?.trim()?.takeIf { it.isNotEmpty() },
                useTls = section.useTls,
                deviceName = deviceName,
            )
        }

        val backup = ConfigBackup(
            app = persisted.app
                ?.let { ConfigBackupAppVersion(it.versionName, it.versionCode) }
                ?: ConfigBackupAppVersion(UNKNOWN_APP_VERSION, 0),
            exportedAt = persisted.exportedAt.orEmpty(),
            haBaseUrl = baseUrl,
            layout = layout,
            dashboardPreferences = persisted.dashboardPreferences?.let {
                DashboardViewPreferences(
                    lastViewId = it.lastViewId,
                    inactivityReturnMinutes = sanitizeInactivityMinutes(it.inactivityReturnMinutes),
                )
            } ?: DashboardViewPreferences(),
            display = persisted.display?.let {
                DisplayPreferences(
                    brightnessPercent = it.brightnessPercent?.let(::sanitizeBrightnessPercent),
                    screenOffTimeoutMinutes = sanitizeNonNegative(it.screenOffTimeoutMinutes),
                    cameraCloseAfterSeconds = sanitizeNonNegative(it.cameraCloseAfterSeconds),
                )
            } ?: DisplayPreferences(),
            mqtt = mqtt,
        )
        return ConfigBackupDecodeResult.Success(backup)
    }

    /** Shown for a file whose `app` section is missing, which our own exports always write. */
    const val UNKNOWN_APP_VERSION = "?"
}
