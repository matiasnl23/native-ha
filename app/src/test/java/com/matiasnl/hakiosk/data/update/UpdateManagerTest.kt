package com.matiasnl.hakiosk.data.update

import android.content.Intent
import java.io.File
import kotlin.random.Random
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class UpdateManagerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val metadataSource = FakeMetadataSource()
    private val downloader = FakeDownloader()
    private val verifier = FakeVerifier()
    private val installer = FakeInstaller()
    private val permission = FakeInstallPermission()
    private val preferences = InMemoryUpdatePreferencesStore()
    private var installedApp = installedApp()

    @Before
    fun setUp() {
        downloader.file = tempFolder.newFile("update.apk").apply { writeText("apk") }
    }

    private fun TestScope.manager() = UpdateManager(
        metadataSource = metadataSource,
        downloader = downloader,
        signatureVerifier = verifier,
        installer = installer,
        installPermission = permission,
        preferencesStore = preferences,
        installedApp = installedApp,
        scope = backgroundScope,
        clock = { EPOCH + testScheduler.currentTime },
        random = NO_JITTER,
    )

    // ---- checking ----

    @Test
    fun `a newer release becomes the available update`() = runTest {
        metadataSource.result = Result.success(metadata(versionCode = 10002))

        val found = manager().checkNow().getOrThrow()

        assertEquals(10002L, found?.versionCode)
        assertEquals("1.0.2", found?.versionName)
    }

    @Test
    fun `the available release is published on the status`() = runTest {
        metadataSource.result = Result.success(metadata(versionCode = 10002))
        val manager = manager()

        manager.checkNow()

        val status = manager.status.value
        assertTrue(status.updateAvailable)
        assertEquals("1.0.2", status.latestVersionName)
        assertEquals(10001L, status.installedVersionCode)
        assertEquals(UpdatePhase.Idle, status.phase)
        assertNull(status.error)
    }

    @Test
    fun `the same versionCode is not an update`() = runTest {
        metadataSource.result = Result.success(metadata(versionCode = 10001))
        val manager = manager()

        assertNull(manager.checkNow().getOrThrow())
        assertFalse(manager.status.value.updateAvailable)
        // Home Assistant shows the installed version as the latest one.
        assertEquals("1.0.1", manager.status.value.latestVersionName)
    }

    @Test
    fun `an older release is never offered`() = runTest {
        metadataSource.result = Result.success(metadata(versionCode = 9000))

        assertNull(manager().checkNow().getOrThrow())
    }

    @Test
    fun `a check records when it ran, even when it fails`() = runTest {
        metadataSource.result = Result.failure(UpdateFailureException(UpdateError.Network("No connection")))
        val manager = manager()

        manager.checkNow()

        assertEquals(EPOCH, preferences.preferences.value.lastCheckEpochMillis)
        assertTrue(manager.status.value.error is UpdateError.Network)
    }

    @Test
    fun `corrupt metadata is reported as such, not as a network error`() = runTest {
        metadataSource.result = Result.failure(InvalidReleaseMetadataException("Malformed release metadata"))
        val manager = manager()

        manager.checkNow()

        assertTrue(manager.status.value.error is UpdateError.InvalidMetadata)
        assertFalse(manager.status.value.updateAvailable)
    }

    @Test
    fun `a release without an APK for this device is an error, not a blind download`() = runTest {
        installedApp = installedApp(installedAbis = listOf("armeabi-v7a"))
        metadataSource.result = Result.success(metadata(versionCode = 10002, abis = listOf("arm64-v8a", "x86")))
        val manager = manager()

        manager.checkNow()

        val error = manager.status.value.error
        assertTrue(error.toString(), error is UpdateError.NoCompatibleApk)
        assertEquals(listOf("arm64-v8a", "x86"), (error as UpdateError.NoCompatibleApk).publishedAbis)
        assertFalse(manager.status.value.updateAvailable)
    }

    @Test
    fun `a release that needs a newer Android is not offered`() = runTest {
        installedApp = installedApp(installedSdkInt = 26)
        metadataSource.result = Result.success(metadata(versionCode = 10002, minSdk = 31))
        val manager = manager()

        manager.checkNow()

        assertEquals(UpdateError.UnsupportedAndroidVersion(31, 26), manager.status.value.error)
        assertFalse(manager.status.value.updateAvailable)
    }

    // ---- downloading, verifying, installing ----

    @Test
    fun `downloads the APK of this device's own ABI and installs it`() = runTest {
        installedApp = installedApp(installedAbis = listOf("armeabi-v7a", "arm64-v8a"))
        metadataSource.result = Result.success(metadata(versionCode = 10002))
        val manager = manager()
        manager.checkNow()

        val result = manager.downloadAndInstall()

        assertTrue(result.isSuccess)
        assertEquals("armeabi-v7a", downloader.requested.single().abi)
        assertEquals(10002L, verifier.verified.single().second)
        assertEquals(1, installer.installed.size)
    }

    @Test
    fun `the downloaded APK is deleted once the installer has it`() = runTest {
        metadataSource.result = Result.success(metadata(versionCode = 10002))
        val manager = manager()
        manager.checkNow()

        manager.downloadAndInstall()

        assertFalse(downloader.file.exists())
        assertEquals(1, downloader.cleared)
    }

    @Test
    fun `a sha256 mismatch installs nothing`() = runTest {
        metadataSource.result = Result.success(metadata(versionCode = 10002))
        downloader.failure = UpdateError.ChecksumMismatch
        val manager = manager()
        manager.checkNow()

        val result = manager.downloadAndInstall()

        assertTrue(result.isFailure)
        assertEquals(UpdateError.ChecksumMismatch, manager.status.value.error)
        assertTrue(verifier.verified.isEmpty())
        assertTrue(installer.installed.isEmpty())
    }

    @Test
    fun `a signature mismatch installs nothing and leaves no APK behind`() = runTest {
        metadataSource.result = Result.success(metadata(versionCode = 10002))
        verifier.failure = UpdateError.SignatureMismatch
        val manager = manager()
        manager.checkNow()

        val result = manager.downloadAndInstall()

        assertTrue(result.isFailure)
        assertEquals(UpdateError.SignatureMismatch, manager.status.value.error)
        assertTrue(installer.installed.isEmpty())
        assertFalse(downloader.file.exists())
    }

    @Test
    fun `an APK that is not this app installs nothing`() = runTest {
        metadataSource.result = Result.success(metadata(versionCode = 10002))
        verifier.failure = UpdateError.UnexpectedApk("The downloaded APK is a different app")
        val manager = manager()
        manager.checkNow()

        manager.downloadAndInstall()

        assertTrue(installer.installed.isEmpty())
        assertTrue(manager.status.value.error is UpdateError.UnexpectedApk)
    }

    @Test
    fun `without the install permission nothing is even downloaded`() = runTest {
        metadataSource.result = Result.success(metadata(versionCode = 10002))
        permission.granted = false
        val manager = manager()
        manager.checkNow()

        val result = manager.downloadAndInstall()

        assertTrue(result.isFailure)
        assertEquals(UpdateError.InstallPermissionMissing, manager.status.value.error)
        assertFalse(manager.status.value.canInstallPackages)
        assertTrue(downloader.requested.isEmpty())
    }

    @Test
    fun `an incompatible signature reported by the installer is kept typed`() = runTest {
        metadataSource.result = Result.success(metadata(versionCode = 10002))
        installer.failure = UpdateError.InstallFailed(InstallFailureReason.CONFLICT, "signatures do not match")
        val manager = manager()
        manager.checkNow()

        manager.downloadAndInstall()

        val error = manager.status.value.error
        assertTrue(error.toString(), error is UpdateError.InstallFailed)
        assertEquals(InstallFailureReason.CONFLICT, (error as UpdateError.InstallFailed).reason)
    }

    @Test
    fun `installing without a known release downloads nothing`() = runTest {
        val manager = manager()

        val result = manager.downloadAndInstall()

        assertTrue(result.isFailure)
        assertTrue(downloader.requested.isEmpty())
        assertTrue(installer.installed.isEmpty())
    }

    @Test
    fun `download progress is published as a percentage`() = runTest {
        metadataSource.result = Result.success(metadata(versionCode = 10002))
        val manager = manager()
        manager.checkNow()
        var midDownload: UpdateStatus? = null
        downloader.observer = { midDownload = manager.status.value }

        manager.downloadAndInstall()

        assertEquals(50, midDownload?.progressPercent)
        assertEquals(true, midDownload?.inProgress)
        // Nothing is left "in progress" once it is done.
        assertNull(manager.status.value.progressPercent)
    }

    @Test
    fun `the pending confirmation is the installer's own state, not a replayed event`() = runTest {
        val manager = manager()

        // Same instance: whoever collects it later still finds a dialog raised before they arrived.
        assertSame(installer.pending, manager.pendingConfirmation)
        assertNull(manager.pendingConfirmation.value)
    }

    // ---- scheduling ----

    @Test
    fun `an interval of zero hours never checks on its own`() = runTest {
        metadataSource.result = Result.success(metadata(versionCode = 10002))
        preferences.setCheckIntervalHours(0)
        val manager = manager()

        manager.start()
        advanceTimeBy(30 * HOUR)

        assertEquals(0, metadataSource.calls)
        assertEquals(0, manager.status.value.checkIntervalHours)
    }

    @Test
    fun `a check that is due runs at startup and then once per interval`() = runTest {
        metadataSource.result = Result.success(metadata(versionCode = 10002))
        preferences.setCheckIntervalHours(6)
        val manager = manager()

        manager.start()
        runCurrent()
        assertEquals(1, metadataSource.calls)

        advanceTimeBy(6 * HOUR + 1)
        assertEquals(2, metadataSource.calls)

        advanceTimeBy(6 * HOUR + 1)
        assertEquals(3, metadataSource.calls)
    }

    @Test
    fun `a restart right after a check waits out the rest of the interval`() = runTest {
        metadataSource.result = Result.success(metadata(versionCode = 10002))
        preferences.setCheckIntervalHours(6)
        preferences.setLastCheckEpochMillis(EPOCH)
        val manager = manager()

        manager.start()
        advanceTimeBy(6 * HOUR - 1)
        assertEquals("checked before the interval elapsed", 0, metadataSource.calls)

        advanceTimeBy(2)
        assertEquals(1, metadataSource.calls)
    }

    @Test
    fun `shortening the interval reschedules the pending wait`() = runTest {
        metadataSource.result = Result.success(metadata(versionCode = 10002))
        preferences.setCheckIntervalHours(24)
        preferences.setLastCheckEpochMillis(EPOCH)
        val manager = manager()
        manager.start()
        advanceTimeBy(HOUR)
        assertEquals(0, metadataSource.calls)

        preferences.setCheckIntervalHours(1)
        runCurrent()

        assertEquals(1, metadataSource.calls)
    }

    @Test
    fun `turning checks off stops the schedule`() = runTest {
        metadataSource.result = Result.success(metadata(versionCode = 10002))
        preferences.setCheckIntervalHours(1)
        val manager = manager()
        manager.start()
        runCurrent()
        assertEquals(1, metadataSource.calls)

        preferences.setCheckIntervalHours(0)
        advanceTimeBy(30 * HOUR)

        assertEquals(1, metadataSource.calls)
    }

    @Test
    fun `stop cancels the schedule and start brings it back`() = runTest {
        metadataSource.result = Result.success(metadata(versionCode = 10002))
        preferences.setCheckIntervalHours(1)
        val manager = manager()
        manager.start()
        runCurrent()

        manager.stop()
        advanceTimeBy(10 * HOUR)
        assertEquals(1, metadataSource.calls)

        manager.start()
        advanceTimeBy(HOUR + 1)
        assertTrue(metadataSource.calls > 1)
    }

    // ---- fakes ----

    private fun installedApp(
        installedVersionCode: Long = 10001,
        installedSdkInt: Int = 29,
        installedAbis: List<String> = listOf("armeabi-v7a"),
    ): InstalledAppInfo = object : InstalledAppInfo {
        override val packageName = "com.matiasnl.hakiosk"
        override val versionCode = installedVersionCode
        override val versionName = "1.0.1"
        override val sdkInt = installedSdkInt
        override val supportedAbis = installedAbis
    }

    private fun metadata(
        versionCode: Long,
        minSdk: Int = 26,
        abis: List<String> = listOf("armeabi-v7a", "arm64-v8a", "universal"),
    ) = ReleaseMetadata(
        versionCode = versionCode,
        versionName = "1.0.2",
        minSdk = minSdk,
        commit = "deadbeef",
        releaseUrl = "https://github.com/matiasnl23/native-ha/releases/tag/v1.0.2",
        apks = abis.associateWith { abi -> ApkAsset(abi, "https://e.invalid/$abi.apk", "a".repeat(64), 1_000) },
    )

    private class FakeMetadataSource : ReleaseMetadataSource {
        var result: Result<ReleaseMetadata> = Result.failure(UpdateFailureException(UpdateError.Network("unset")))
        var calls = 0

        override suspend fun fetch(): Result<ReleaseMetadata> {
            calls++
            return result
        }
    }

    private class FakeDownloader : ApkDownloadSource {
        lateinit var file: File
        var failure: UpdateError? = null
        var observer: (() -> Unit)? = null
        val requested = mutableListOf<ApkAsset>()
        var cleared = 0

        override suspend fun download(asset: ApkAsset, onProgress: (Long, Long) -> Unit): Result<File> {
            requested += asset
            onProgress(asset.sizeBytes / 2, asset.sizeBytes)
            observer?.invoke()
            failure?.let { return Result.failure(UpdateFailureException(it)) }
            return Result.success(file)
        }

        override fun clear() {
            cleared++
        }
    }

    private class FakeVerifier : ApkSignatureVerifier {
        var failure: UpdateError? = null
        val verified = mutableListOf<Pair<File, Long>>()

        override fun verify(apk: File, expectedVersionCode: Long): Result<Unit> {
            verified += apk to expectedVersionCode
            return failure?.let { Result.failure(UpdateFailureException(it)) } ?: Result.success(Unit)
        }
    }

    private class FakeInstaller : ApkInstaller {
        val pending = MutableStateFlow<PendingInstallConfirmation?>(null)
        override val pendingConfirmation: StateFlow<PendingInstallConfirmation?> = pending
        val launched = mutableListOf<PendingInstallConfirmation>()
        var failure: UpdateError? = null
        val installed = mutableListOf<File>()

        override fun confirmationLaunched(confirmation: PendingInstallConfirmation) {
            launched += confirmation
        }

        override suspend fun install(apk: File): Result<Unit> {
            installed += apk
            return failure?.let { Result.failure(UpdateFailureException(it)) } ?: Result.success(Unit)
        }
    }

    private class FakeInstallPermission(var granted: Boolean = true) : InstallPermission {
        override fun canInstallPackages(): Boolean = granted

        override fun settingsIntent(): Intent = error("The settings intent is never launched from a test")
    }

    private companion object {
        const val EPOCH = 1_700_000_000_000L
        const val HOUR = 60L * 60 * 1000

        /** Deterministic schedule: jitter is real in production, noise in a test. */
        val NO_JITTER = object : Random() {
            override fun nextBits(bitCount: Int): Int = 0
        }
    }
}
