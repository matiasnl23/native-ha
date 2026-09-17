package com.matiasnl.hakiosk.data.update

import android.content.pm.PackageInstaller
import org.junit.Assert.assertEquals
import org.junit.Test

class InstallStatusesTest {

    @Test
    fun `maps every failure the installer can report`() {
        assertEquals(InstallFailureReason.CONFLICT, InstallStatuses.reasonFor(PackageInstaller.STATUS_FAILURE_CONFLICT))
        assertEquals(InstallFailureReason.STORAGE, InstallStatuses.reasonFor(PackageInstaller.STATUS_FAILURE_STORAGE))
        assertEquals(InstallFailureReason.ABORTED, InstallStatuses.reasonFor(PackageInstaller.STATUS_FAILURE_ABORTED))
        assertEquals(InstallFailureReason.INVALID, InstallStatuses.reasonFor(PackageInstaller.STATUS_FAILURE_INVALID))
        assertEquals(
            InstallFailureReason.INCOMPATIBLE,
            InstallStatuses.reasonFor(PackageInstaller.STATUS_FAILURE_INCOMPATIBLE),
        )
        assertEquals(InstallFailureReason.BLOCKED, InstallStatuses.reasonFor(PackageInstaller.STATUS_FAILURE_BLOCKED))
    }

    @Test
    fun `an unknown status is not mistaken for a known failure`() {
        assertEquals(InstallFailureReason.UNKNOWN, InstallStatuses.reasonFor(PackageInstaller.STATUS_FAILURE))
        assertEquals(InstallFailureReason.UNKNOWN, InstallStatuses.reasonFor(Int.MIN_VALUE))
        assertEquals(InstallFailureReason.UNKNOWN, InstallStatuses.reasonFor(9999))
    }
}
