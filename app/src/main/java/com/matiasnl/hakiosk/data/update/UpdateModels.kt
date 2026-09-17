package com.matiasnl.hakiosk.data.update

/**
 * One APK file of a release, as published in `release-metadata.json`. Every APK of a release shares the
 * release's `versionCode`: there are no per-ABI offsets, so the metadata's version is the one a tablet
 * compares against no matter which file it ends up downloading. See `docs/RELEASE-OTA.md`.
 */
data class ApkAsset(
    /** ABI this file was built for, or [ReleaseMetadata.UNIVERSAL_ABI]. */
    val abi: String,
    val url: String,
    /** Lowercase hex, 64 chars. Checked against the downloaded bytes before anything is installed. */
    val sha256: String,
    val sizeBytes: Long,
)

/**
 * The `release-metadata.json` published next to the APKs of a GitHub release, read from the stable
 * `releases/latest/download/` URL (never the GitHub API, which is rate limited to 60 requests per hour
 * per IP without a token). Only entries the app could actually use survive parsing; see
 * [ReleaseMetadataParser].
 */
data class ReleaseMetadata(
    val versionCode: Long,
    val versionName: String,
    /** Minimum Android SDK the release supports. */
    val minSdk: Int,
    val commit: String?,
    /** Release page on GitHub, shown to the user and published to Home Assistant. */
    val releaseUrl: String?,
    /** Keyed by ABI, plus [UNIVERSAL_ABI]. */
    val apks: Map<String, ApkAsset>,
) {
    fun isNewerThan(installedVersionCode: Long): Boolean = versionCode > installedVersionCode

    /**
     * The APK to download on a device reporting [supportedAbis] (`Build.SUPPORTED_ABIS`, in the
     * device's own preference order): the first listed ABI that this release publishes, falling back to
     * the universal APK. Null means the release has nothing this device can install, which is reported
     * as an error instead of downloading an APK Android would reject. Never picks by `Build.CPU_ABI`
     * (deprecated) and never assumes arm64: the production tablet is armeabi-v7a.
     */
    fun apkFor(supportedAbis: List<String>): ApkAsset? =
        supportedAbis.firstNotNullOfOrNull { apks[it] } ?: apks[UNIVERSAL_ABI]

    companion object {
        const val UNIVERSAL_ABI = "universal"
    }
}

/** Why the installer session ended without installing. Mapped from `PackageInstaller.STATUS_FAILURE_*`. */
enum class InstallFailureReason {
    /** `STATUS_FAILURE_CONFLICT`: already installed with an incompatible signature. */
    CONFLICT,

    /** `STATUS_FAILURE_STORAGE`. */
    STORAGE,

    /** `STATUS_FAILURE_ABORTED`: the user (or the system) dismissed the confirmation dialog. */
    ABORTED,

    /** `STATUS_FAILURE_INVALID`: the APK is malformed. */
    INVALID,

    /** `STATUS_FAILURE_INCOMPATIBLE`: the APK doesn't run on this device (ABI, minSdk). */
    INCOMPATIBLE,

    /** `STATUS_FAILURE_BLOCKED`: blocked by the device policy or a verifier. */
    BLOCKED,

    /** The session was created but nobody confirmed the dialog in time. */
    TIMEOUT,

    UNKNOWN,
}

/** Everything that can stop an update, typed so the UI and Home Assistant can tell them apart. */
sealed interface UpdateError {
    /** The metadata or the APK couldn't be fetched. Never carries request headers or user URLs. */
    data class Network(val message: String) : UpdateError

    /** The metadata was reachable but unusable (corrupt JSON, missing or invalid fields). */
    data class InvalidMetadata(val message: String) : UpdateError

    /** The release publishes no APK for this device's ABIs and no universal one. */
    data class NoCompatibleApk(val publishedAbis: List<String>) : UpdateError

    data class UnsupportedAndroidVersion(val requiredSdk: Int, val deviceSdk: Int) : UpdateError

    data class NotEnoughSpace(val requiredBytes: Long, val availableBytes: Long) : UpdateError

    /** The downloaded bytes don't hash to the published SHA-256. Nothing is installed. */
    data object ChecksumMismatch : UpdateError

    /** The APK is signed with a different key than the installed app. Nothing is installed. */
    data object SignatureMismatch : UpdateError

    /** The APK identifies as another package or another version than the metadata promised. */
    data class UnexpectedApk(val message: String) : UpdateError

    /** "Install unknown apps" is off for this app; the user has to grant it in Settings once. */
    data object InstallPermissionMissing : UpdateError

    data class InstallFailed(val reason: InstallFailureReason, val message: String) : UpdateError
}

/** Where the update client is in the check → download → verify → install flow. */
sealed interface UpdatePhase {
    /** Nothing happening. [UpdateStatus.available] says whether a newer release is known. */
    data object Idle : UpdatePhase

    data object Checking : UpdatePhase

    data class Downloading(val downloadedBytes: Long, val totalBytes: Long) : UpdatePhase

    /** Hashing the file and comparing its signing certificate against the installed app's. */
    data object Verifying : UpdatePhase

    /** The APK was handed to the system installer; the confirmation dialog is up (or about to be). */
    data object Installing : UpdatePhase

    data class Failed(val error: UpdateError) : UpdatePhase
}

/**
 * Observable state of the update client. Shaped for its two consumers: the settings screen (stage 3)
 * and the Home Assistant `update` entity (stage 4), which needs installed vs. latest version, the
 * release URL, whether an install is in progress and a percentage.
 */
data class UpdateStatus(
    val installedVersionCode: Long = 0,
    val installedVersionName: String = "",
    /** The newest release found, only when it is newer than the installed one; null otherwise. */
    val available: ReleaseMetadata? = null,
    val phase: UpdatePhase = UpdatePhase.Idle,
    val lastCheckEpochMillis: Long? = null,
    /** Hours between automatic checks; 0 = automatic checks off. */
    val checkIntervalHours: Int = DEFAULT_CHECK_INTERVAL_HOURS,
    /** `canRequestPackageInstalls()`: false means the install will be refused until the user grants it. */
    val canInstallPackages: Boolean = true,
) {
    val updateAvailable: Boolean get() = available != null

    /** Version Home Assistant shows as "latest": the available one, or the installed one when up to date. */
    val latestVersionName: String get() = available?.versionName ?: installedVersionName

    /** True while downloading, verifying or installing (Home Assistant's `in_progress`). */
    val inProgress: Boolean
        get() = phase is UpdatePhase.Downloading || phase is UpdatePhase.Verifying || phase is UpdatePhase.Installing

    /** 0..100 while downloading with a known size, null otherwise (Home Assistant's `update_percentage`). */
    val progressPercent: Int?
        get() = (phase as? UpdatePhase.Downloading)
            ?.takeIf { it.totalBytes > 0 }
            ?.let { ((it.downloadedBytes * 100) / it.totalBytes).toInt().coerceIn(0, 100) }

    val error: UpdateError? get() = (phase as? UpdatePhase.Failed)?.error
}

/** Daily: releases are rare and the check is a single HTTPS GET, so there is nothing to gain from more. */
const val DEFAULT_CHECK_INTERVAL_HOURS = 24

/** Identity of the running app, so the update logic stays free of Android APIs and stays unit testable. */
interface InstalledAppInfo {
    val packageName: String

    /** `PackageInfo.longVersionCode`, the value releases must keep growing. */
    val versionCode: Long

    val versionName: String

    val sdkInt: Int

    /** `Build.SUPPORTED_ABIS`, in the device's preference order. */
    val supportedAbis: List<String>
}
