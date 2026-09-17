package com.matiasnl.hakiosk.data.update

import android.content.Intent
import android.content.pm.PackageInstaller
import java.io.File
import kotlinx.coroutines.flow.Flow

/** Hands a verified APK to the system package installer. */
interface ApkInstaller {
    /**
     * Confirmation dialogs the caller must launch, from a **visible Activity**. They are never launched
     * from here: this code can run from a service, and Android 10+ blocks activity starts from the
     * background, so the intent would be silently dropped. The settings screen (stage 3) collects this
     * and calls `startActivity`.
     */
    val userConfirmations: Flow<Intent>

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
