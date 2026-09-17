package com.matiasnl.hakiosk.data.update

import android.content.Intent
import android.content.pm.PackageInstaller
import java.io.File
import kotlinx.coroutines.flow.StateFlow

/**
 * A system confirmation dialog the installer is blocked on, waiting for someone to launch it.
 *
 * Identity matters: [ApkInstaller.confirmationLaunched] clears only the confirmation it is handed, so a
 * dialog raised by a newer session is never dropped by a late call from an older one.
 */
class PendingInstallConfirmation(val intent: Intent)

/** Hands a verified APK to the system package installer. */
interface ApkInstaller {
    /**
     * The confirmation dialog waiting to be launched, or null when there is none.
     *
     * **State, not an event, on purpose.** The system raises it whenever it feels like it, and if
     * nobody happens to be listening at that instant the install stalls until it times out, with the
     * session open and a download wasted. Holding it as state means whoever comes along next still
     * finds it. It is cleared when [confirmationLaunched] is called and when the session ends.
     *
     * **Must be collected from `MainActivity`, not only from the updates screen.** The two paths that
     * matter both happen with something else on screen: Home Assistant asking the tablet to install
     * while it shows the dashboard, and the user tapping "install" and navigating away before the
     * system answers. It also has to be launched from a **visible Activity** — Android 10+ drops
     * activity starts from the background.
     */
    val pendingConfirmation: StateFlow<PendingInstallConfirmation?>

    /** Called once [PendingInstallConfirmation.intent] has been launched, so it is not shown twice. */
    fun confirmationLaunched(confirmation: PendingInstallConfirmation)

    /**
     * Copies [apk] into an installer session and commits it, then waits for the outcome. Fails with an
     * [UpdateFailureException] carrying [UpdateError.InstallFailed].
     *
     * On success the caller usually never sees the result: the system kills this process before
     * finishing the install (`setDontKillApp` is API 34 and only applies to splits).
     */
    suspend fun install(apk: File): Result<Unit>
}

/** Maps `PackageInstaller.STATUS_FAILURE_*` to something the UI and Home Assistant can explain. */
object InstallStatuses {
    fun reasonFor(status: Int): InstallFailureReason = when (status) {
        // The installed app is signed with a different key: the only fix is uninstall + install, which
        // wipes the user's config, so it must never happen silently. See docs/RELEASE-OTA.md.
        PackageInstaller.STATUS_FAILURE_CONFLICT -> InstallFailureReason.CONFLICT
        PackageInstaller.STATUS_FAILURE_STORAGE -> InstallFailureReason.STORAGE
        // Also what a dismissed confirmation dialog reports.
        PackageInstaller.STATUS_FAILURE_ABORTED -> InstallFailureReason.ABORTED
        PackageInstaller.STATUS_FAILURE_INVALID -> InstallFailureReason.INVALID
        PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> InstallFailureReason.INCOMPATIBLE
        PackageInstaller.STATUS_FAILURE_BLOCKED -> InstallFailureReason.BLOCKED
        else -> InstallFailureReason.UNKNOWN
    }
}
