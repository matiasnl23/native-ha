package com.matiasnl.hakiosk.data.update

import android.content.Intent
import kotlin.random.Random
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Drives over-the-air updates: when to look, what to download, what to verify and what to install, and
 * publishes all of it through [status] for the settings screen and the Home Assistant `update` entity.
 *
 * **Scheduling.** A coroutine in the app process, not WorkManager: a kiosk process is always alive, so
 * a new dependency would buy nothing. The interval is configurable in hours (0 = off) and each wait
 * carries jitter so several tablets never hit GitHub at the same second. A check runs at startup *when
 * one is due* — the last check is persisted, so a tablet that reboots in a loop can't turn into a
 * polling loop.
 *
 * **What is automatic and what is not.** Only the check is automatic. Downloading and installing are
 * explicit ([requestInstall]), because without Device Owner the system shows a confirmation dialog that
 * somebody has to tap on the tablet anyway, and because a 60 MB download shouldn't start behind the
 * user's back.
 *
 * **Order of verification.** SHA-256 first (the file is what the release published), signing
 * certificate second (it came from the release key). Only then does anything reach the installer; a
 * failure at either step installs nothing and deletes the file.
 */
class UpdateManager(
    private val metadataSource: ReleaseMetadataSource,
    private val downloader: ApkDownloadSource,
    private val signatureVerifier: ApkSignatureVerifier,
    private val installer: ApkInstaller,
    private val installPermission: InstallPermission,
    private val preferencesStore: UpdatePreferencesStore,
    private val installedApp: InstalledAppInfo,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
    private val random: Random = Random.Default,
    private val log: (String) -> Unit = {},
) {
    private val _status = MutableStateFlow(
        UpdateStatus(
            installedVersionCode = installedApp.versionCode,
            installedVersionName = installedApp.versionName,
            canInstallPackages = installPermission.canInstallPackages(),
        ),
    )
    val status: StateFlow<UpdateStatus> = _status.asStateFlow()

    /**
     * The system confirmation dialog waiting to be launched, or null. Held as state so it survives
     * until somebody can show it: see [ApkInstaller.pendingConfirmation], which also explains why this
     * has to be collected from `MainActivity` and launched from a **visible Activity**.
     */
    val pendingConfirmation: StateFlow<PendingInstallConfirmation?> get() = installer.pendingConfirmation

    /** Call after launching [PendingInstallConfirmation.intent] so the dialog isn't offered twice. */
    fun confirmationLaunched(confirmation: PendingInstallConfirmation) {
        installer.confirmationLaunched(confirmation)
    }

    /** Only one of check / download / install runs at a time. */
    private val mutex = Mutex()

    private val lock = Any()
    private var scheduleJob: Job? = null

    /** Starts the automatic check schedule. Idempotent: safe to call from every `onStart`. */
    fun start() {
        synchronized(lock) {
            if (scheduleJob?.isActive == true) return
            scheduleJob = scope.launch { scheduleLoop() }
        }
    }

    fun stop() {
        synchronized(lock) {
            scheduleJob?.cancel()
            scheduleJob = null
        }
    }

    /** Re-reads "install unknown apps", which the user can grant or revoke while the app runs. */
    fun refreshInstallPermission() {
        _status.update { it.copy(canInstallPackages = installPermission.canInstallPackages()) }
    }

    /** Fire-and-forget check for the UI's "check now" button and for Home Assistant. */
    fun requestCheck() {
        scope.launch { quietly("check") { checkNow() } }
    }

    /** Fire-and-forget download + verify + install. Ignored while another step is running. */
    fun requestInstall() {
        if (_status.value.inProgress) return
        scope.launch { quietly("install") { downloadAndInstall() } }
    }

    /**
     * Looks for a newer release. Returns the release when there is one, null when the tablet is up to
     * date, and a typed failure otherwise. Records the attempt either way so a broken network doesn't
     * turn into a retry loop.
     */
    suspend fun checkNow(): Result<ReleaseMetadata?> = mutex.withLock {
        setPhase(UpdatePhase.Checking)
        try {
            metadataSource.fetch().fold(
                onSuccess = { metadata -> evaluate(metadata) },
                onFailure = { error -> fail(error.toUpdateError()) },
            )
        } finally {
            // In a finally, and NonCancellable: an attempt that happened must be recorded even when the
            // fetch threw or was cancelled. Otherwise the schedule sees the old timestamp, computes "due
            // now" again and retries immediately, turning one broken check into a tight loop.
            withContext(NonCancellable) { preferencesStore.setLastCheckEpochMillis(clock()) }
        }
    }

    private fun evaluate(metadata: ReleaseMetadata): Result<ReleaseMetadata?> = when {
        !metadata.isNewerThan(installedApp.versionCode) -> {
            log("Update check: already on the latest version (${installedApp.versionCode})")
            _status.update { it.copy(available = null, phase = UpdatePhase.Idle) }
            Result.success(null)
        }

        metadata.minSdk > installedApp.sdkInt ->
            fail(UpdateError.UnsupportedAndroidVersion(metadata.minSdk, installedApp.sdkInt))

        metadata.apkFor(installedApp.supportedAbis) == null ->
            fail(UpdateError.NoCompatibleApk(metadata.apks.keys.sorted()))

        else -> {
            log("Update available: ${metadata.versionName} (${metadata.versionCode})")
            _status.update { it.copy(available = metadata, phase = UpdatePhase.Idle) }
            Result.success(metadata)
        }
    }

    /**
     * Downloads the APK of the release found by [checkNow], verifies it and hands it to the installer.
     * The APK is deleted on the way out, whatever happened: it lives in internal storage but there is no
     * reason to keep a copy of an installer around.
     */
    suspend fun downloadAndInstall(): Result<Unit> = mutex.withLock {
        val metadata = _status.value.available
            ?: return@withLock fail(UpdateError.InvalidMetadata("No update to install"))
        refreshInstallPermission()
        if (!installPermission.canInstallPackages()) return@withLock fail(UpdateError.InstallPermissionMissing)
        val asset = metadata.apkFor(installedApp.supportedAbis)
            ?: return@withLock fail(UpdateError.NoCompatibleApk(metadata.apks.keys.sorted()))

        log("Downloading ${metadata.versionName} for ${asset.abi}")
        setPhase(UpdatePhase.Downloading(0, asset.sizeBytes))
        val apk = downloader.download(asset) { done, total ->
            setPhase(UpdatePhase.Downloading(done, total))
        }.getOrElse { error -> return@withLock fail(error.toUpdateError()) }

        try {
            setPhase(UpdatePhase.Verifying)
            signatureVerifier.verify(apk, metadata.versionCode)
                .getOrElse { error -> return@withLock fail(error.toUpdateError()) }

            log("Committing the install session")
            setPhase(UpdatePhase.Installing)
            installer.install(apk)
                .getOrElse { error -> return@withLock fail(error.toUpdateError()) }
            // Rarely reached: the system kills this process to finish the install.
            _status.update { it.copy(phase = UpdatePhase.Idle) }
            Result.success(Unit)
        } finally {
            apk.delete()
            downloader.clear()
        }
    }

    /** Settings screen where the user grants "install unknown apps", for stage 3 to launch. */
    fun installPermissionIntent(): Intent = installPermission.settingsIntent()

    private suspend fun scheduleLoop() = coroutineScope {
        launch {
            preferencesStore.preferences.collect { prefs ->
                _status.update {
                    it.copy(
                        checkIntervalHours = prefs.checkIntervalHours,
                        lastCheckEpochMillis = prefs.lastCheckEpochMillis,
                    )
                }
            }
        }
        // Only the interval drives the schedule, and collectLatest cancels the pending wait as soon as
        // it changes (a new 1 h interval must not wait out the old 24 h one). Deliberately NOT the whole
        // preferences flow: a check writes its own timestamp there, and collectLatest would then cancel
        // the very check that is running, leaving the state stuck on "checking".
        preferencesStore.preferences
            .map { it.checkIntervalHours }
            .distinctUntilChanged()
            .collectLatest { hours ->
                if (hours <= 0) {
                    log("Automatic update checks are off")
                    awaitCancellation()
                }
                val intervalMillis = hours * MILLIS_PER_HOUR
                while (true) {
                    delay(waitUntilNextCheck(intervalMillis))
                    checkQuietly()
                }
            }
    }

    /**
     * Time left until the next check: the remainder of the interval since the last one (0 when it is
     * already due, which is what makes a startup check happen), plus up to 10 % of jitter. Clamped to
     * the interval so a clock that jumped forward can't park the next check in the far future.
     */
    private suspend fun waitUntilNextCheck(intervalMillis: Long): Long {
        val lastCheck = preferencesStore.preferences.first().lastCheckEpochMillis ?: 0L
        val remaining = (lastCheck + intervalMillis - clock()).coerceIn(0, intervalMillis)
        return remaining + random.nextLong(intervalMillis / JITTER_FRACTION + 1)
    }

    private suspend fun checkQuietly() = quietly("check") { checkNow() }

    /**
     * Runs a step without ever letting a failure escape to the coroutine's uncaught handler.
     *
     * The steps below return typed failures, but they can also *throw*: `ApkDownloader` and
     * `PackageInstallerApkInstaller` rethrow anything that isn't an [UpdateFailureException] or an
     * `IOException`, so a `RuntimeException` from Binder in `createSession`, or an
     * [IllegalStateException] from okio, would reach the default handler. On a tablet that runs 24/7
     * that means the app dies and the wall display sits on the launcher until somebody walks over to it.
     */
    private suspend fun quietly(what: String, block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log("Update $what failed: ${e.javaClass.simpleName}")
            setPhase(UpdatePhase.Failed(e.toUpdateError()))
        }
    }

    private fun setPhase(phase: UpdatePhase) {
        _status.update { it.copy(phase = phase) }
    }

    private fun <T> fail(error: UpdateError): Result<T> {
        setPhase(UpdatePhase.Failed(error))
        return Result.failure(UpdateFailureException(error))
    }

    private companion object {
        const val MILLIS_PER_HOUR = 60L * 60 * 1000

        /** Up to a tenth of the interval, so tablets on the same interval drift apart. */
        const val JITTER_FRACTION = 10
    }
}
