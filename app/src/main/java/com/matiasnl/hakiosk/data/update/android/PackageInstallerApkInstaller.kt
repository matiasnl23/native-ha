package com.matiasnl.hakiosk.data.update.android

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInstaller
import android.os.Build
import android.os.SystemClock
import androidx.annotation.VisibleForTesting
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import com.matiasnl.hakiosk.data.update.ApkInstaller
import com.matiasnl.hakiosk.data.update.PendingInstallConfirmation
import com.matiasnl.hakiosk.data.update.InstallFailureReason
import com.matiasnl.hakiosk.data.update.InstallStatuses
import com.matiasnl.hakiosk.data.update.UpdateError
import com.matiasnl.hakiosk.data.update.UpdateFailureException
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Installs an APK through [PackageInstaller] sessions. Not `ACTION_INSTALL_PACKAGE`, which is
 * deprecated since API 29 and reports failures as an unusable "app not installed".
 *
 * Flow: create a session in `MODE_FULL_INSTALL`, stream the APK into it, `fsync`, close, `commit` with
 * an [android.content.IntentSender] pointing at a private broadcast. The system answers on that
 * broadcast: `STATUS_PENDING_USER_ACTION` carries the confirmation dialog to launch (published on
 * [userConfirmations], never launched from here), and a later broadcast carries the real outcome.
 *
 * Sideloaded without Device Owner, the dialog always appears; with Device Owner the system skips the
 * permission check and never emits `STATUS_PENDING_USER_ACTION` at all, so the same code installs
 * silently if the tablet is ever provisioned.
 */
class PackageInstallerApkInstaller(
    context: Context,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val log: (String) -> Unit = {},
) : ApkInstaller {
    private val appContext = context.applicationContext

    private val _pendingConfirmation = MutableStateFlow<PendingInstallConfirmation?>(null)
    override val pendingConfirmation: StateFlow<PendingInstallConfirmation?> = _pendingConfirmation.asStateFlow()

    override fun confirmationLaunched(confirmation: PendingInstallConfirmation) {
        // Only clears this exact confirmation: a late call from a previous session must not swallow the
        // dialog of the current one.
        _pendingConfirmation.compareAndSet(confirmation, null)
    }

    override suspend fun install(apk: File): Result<Unit> = withContext(ioDispatcher) {
        val installer = appContext.packageManager.packageInstaller
        // Unique per attempt: a stale PendingIntent from an abandoned session must never resolve here.
        val statusAction = "${appContext.packageName}.UPDATE_STATUS.${SystemClock.elapsedRealtime()}"
        val outcome = CompletableDeferred<Result<Unit>>()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) = onStatus(intent, outcome)
        }
        // NOT_EXPORTED is enough: the broadcast is sent by our own PendingIntent, under our own uid.
        ContextCompat.registerReceiver(
            appContext,
            receiver,
            IntentFilter(statusAction),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        var sessionId = -1
        try {
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
                setAppPackageName(appContext.packageName)
                // Lets the system reserve space up front and fail early instead of half way through.
                setSize(apk.length())
            }
            sessionId = installer.createSession(params)
            installer.openSession(sessionId).use { session ->
                session.openWrite(APK_ENTRY_NAME, 0, apk.length()).use { output ->
                    apk.inputStream().use { input -> input.copyTo(output) }
                    // Must happen before the stream is closed, or the session may commit short bytes.
                    session.fsync(output)
                }
                session.commit(statusIntentSender(statusAction, sessionId))
            }
            // The dialog is up (or, with Device Owner, the install is already running).
            withTimeoutOrNull(OUTCOME_TIMEOUT_MILLIS) { outcome.await() }
                ?: abandon(installer, sessionId).let {
                    failure(InstallFailureReason.TIMEOUT, "Nobody confirmed the install on the tablet")
                }
        } catch (e: IOException) {
            abandon(installer, sessionId)
            failure(InstallFailureReason.STORAGE, e.message ?: "Could not write the install session")
        } catch (e: SecurityException) {
            abandon(installer, sessionId)
            failure(InstallFailureReason.BLOCKED, e.message ?: "The system refused the install session")
        } catch (e: Throwable) {
            // Cancellation included: never leave a committed-but-orphaned session behind.
            abandon(installer, sessionId)
            throw e
        } finally {
            // The session is over one way or another: a dialog still on offer would now be stale.
            _pendingConfirmation.value = null
            runCatching { appContext.unregisterReceiver(receiver) }
        }
    }

    @VisibleForTesting
    internal fun onStatus(intent: Intent, outcome: CompletableDeferred<Result<Unit>>) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, Int.MIN_VALUE)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE).orEmpty()
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirmation = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java)
                if (confirmation == null) {
                    outcome.complete(failure(InstallFailureReason.UNKNOWN, "The system asked for a confirmation it didn't provide"))
                } else {
                    log("Update install waiting for the confirmation dialog")
                    // Published as state, not emitted as an event: with no collector at this instant an
                    // event would be dropped and the session would hang until it timed out. Keeps
                    // waiting either way — the real outcome arrives on a second broadcast.
                    _pendingConfirmation.value = PendingInstallConfirmation(confirmation)
                }
            }

            PackageInstaller.STATUS_SUCCESS -> {
                log("Update installed")
                outcome.complete(Result.success(Unit))
            }

            else -> {
                val reason = InstallStatuses.reasonFor(status)
                log("Update install failed: $reason")
                outcome.complete(failure(reason, message.ifEmpty { "Install failed ($reason)" }))
            }
        }
    }

    private fun statusIntentSender(action: String, sessionId: Int) = PendingIntent.getBroadcast(
        appContext,
        sessionId,
        Intent(action).setPackage(appContext.packageName),
        pendingIntentFlags(),
    ).intentSender

    /**
     * Mutable on purpose where the platform knows the difference: the system fills the status extras
     * into this intent, and an immutable one would arrive empty.
     */
    private fun pendingIntentFlags(): Int = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
    } else {
        PendingIntent.FLAG_UPDATE_CURRENT
    }

    private fun abandon(installer: PackageInstaller, sessionId: Int) {
        if (sessionId < 0) return
        runCatching { installer.abandonSession(sessionId) }
    }

    private fun failure(reason: InstallFailureReason, message: String): Result<Unit> =
        Result.failure(UpdateFailureException(UpdateError.InstallFailed(reason, message)))

    private companion object {
        const val APK_ENTRY_NAME = "hakiosk-update.apk"

        /** The dialog needs a person in front of the tablet; after this the session is abandoned. */
        const val OUTCOME_TIMEOUT_MILLIS = 10L * 60 * 1000
    }
}
