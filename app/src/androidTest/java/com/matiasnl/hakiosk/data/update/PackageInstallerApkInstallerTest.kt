package com.matiasnl.hakiosk.data.update

import android.content.Intent
import android.content.pm.PackageInstaller
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.matiasnl.hakiosk.data.update.android.PackageInstallerApkInstaller
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The installer's state handling, driven with the status broadcasts the system would send.
 *
 * **Installs nothing**: no session is ever committed. The session test creates one, writes to it and
 * abandons it, which is the same code path the real install takes up to (but not including) `commit`.
 *
 * Run it (never `connectedAndroidTest`, which uninstalls the app and wipes the user's config):
 * `adb shell am instrument -w -e class com.matiasnl.hakiosk.data.update.PackageInstallerApkInstallerTest com.matiasnl.hakiosk.test/androidx.test.runner.AndroidJUnitRunner`
 */
@RunWith(AndroidJUnit4::class)
class PackageInstallerApkInstallerTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val installer = PackageInstallerApkInstaller(context)

    @Test
    fun aConfirmationRaisedWithNobodyListeningIsStillThereAfterwards() {
        // The regression this guards: as an event (SharedFlow with no replay) this intent was dropped
        // whenever no collector happened to be subscribed at that instant — Home Assistant asking for an
        // install while the dashboard is on screen — and the session then hung until it timed out.
        val outcome = CompletableDeferred<Result<Unit>>()

        installer.onStatus(pendingUserActionStatus(), outcome)

        val pending = installer.pendingConfirmation.value
        assertNotNull("The confirmation was dropped: nobody was collecting when it arrived", pending)
        assertEquals(CONFIRMATION_ACTION, pending!!.intent.action)
        // Still waiting for the real outcome, which arrives on a second broadcast.
        assertFalse(outcome.isCompleted)
    }

    @Test
    fun launchingTheConfirmationClearsIt() {
        installer.onStatus(pendingUserActionStatus(), CompletableDeferred())
        val pending = installer.pendingConfirmation.value!!

        installer.confirmationLaunched(pending)

        assertNull(installer.pendingConfirmation.value)
    }

    @Test
    fun aStaleConfirmationDoesNotClearTheCurrentOne() {
        installer.onStatus(pendingUserActionStatus(), CompletableDeferred())
        val stale = installer.pendingConfirmation.value!!
        // A second session raises its own dialog before anyone got round to the first.
        installer.onStatus(pendingUserActionStatus(), CompletableDeferred())
        val current = installer.pendingConfirmation.value!!

        installer.confirmationLaunched(stale)

        assertSame(current, installer.pendingConfirmation.value)
    }

    @Test
    fun aFailureStatusIsReportedWithItsReason() {
        val outcome = CompletableDeferred<Result<Unit>>()
        val status = Intent(STATUS_ACTION).apply {
            putExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE_CONFLICT)
            putExtra(PackageInstaller.EXTRA_STATUS_MESSAGE, "signatures do not match")
        }

        installer.onStatus(status, outcome)

        val error = runBlocking { outcome.await() }.exceptionOrNull()?.toUpdateError()
        assertTrue(error.toString(), error is UpdateError.InstallFailed)
        assertEquals(InstallFailureReason.CONFLICT, (error as UpdateError.InstallFailed).reason)
    }

    @Test
    fun aSessionCanBeCreatedWrittenAndAbandonedWithoutInstallingAnything() {
        val packageInstaller = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            setSize(BYTES.size.toLong())
        }
        val sessionId = packageInstaller.createSession(params)
        try {
            packageInstaller.openSession(sessionId).use { session ->
                session.openWrite("hakiosk-update.apk", 0, BYTES.size.toLong()).use { output ->
                    output.write(BYTES)
                    // Must happen before the stream closes, exactly as the installer does it.
                    session.fsync(output)
                }
            }
            assertTrue(packageInstaller.mySessions.any { it.sessionId == sessionId })
        } finally {
            packageInstaller.abandonSession(sessionId)
        }

        assertFalse(packageInstaller.mySessions.any { it.sessionId == sessionId })
    }

    private fun pendingUserActionStatus() = Intent(STATUS_ACTION).apply {
        putExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_PENDING_USER_ACTION)
        putExtra(Intent.EXTRA_INTENT, Intent(CONFIRMATION_ACTION))
    }

    private companion object {
        const val STATUS_ACTION = "com.matiasnl.hakiosk.test.UPDATE_STATUS"
        const val CONFIRMATION_ACTION = "com.matiasnl.hakiosk.test.CONFIRM"
        val BYTES = "not a real APK, this session is abandoned".toByteArray()
    }
}
