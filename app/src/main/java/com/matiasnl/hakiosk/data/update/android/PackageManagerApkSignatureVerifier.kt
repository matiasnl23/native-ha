package com.matiasnl.hakiosk.data.update.android

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.pm.PackageInfoCompat
import com.matiasnl.hakiosk.data.update.ApkSignatureVerifier
import com.matiasnl.hakiosk.data.update.ApkSigningCertificates
import com.matiasnl.hakiosk.data.update.UpdateError
import com.matiasnl.hakiosk.data.update.UpdateFailureException
import java.io.File

/**
 * Compares the signing certificate of a downloaded APK against the installed app's, through
 * `PackageManager`.
 *
 * **The flags matter.** `getPackageArchiveInfo` only collects certificates when `GET_SIGNATURES` is
 * set: in Android 9, 10 and 13 asking for `GET_SIGNING_CERTIFICATES` alone comes back with
 * `signingInfo == null` and no `signatures` either, so a naive "use the modern flag" implementation
 * silently fails to verify exactly on the Android 10 tablet this app runs on. Both flags always go
 * together (see [ARCHIVE_FLAGS]), `signingInfo` is read on API 28+ and `signatures` on 26/27.
 */
class PackageManagerApkSignatureVerifier(context: Context) : ApkSignatureVerifier {
    private val appContext = context.applicationContext
    private val packageManager get() = appContext.packageManager

    override fun verify(apk: File, expectedVersionCode: Long): Result<Unit> = try {
        Result.success(check(apk, expectedVersionCode))
    } catch (e: UpdateFailureException) {
        Result.failure(e)
    }

    private fun check(apk: File, expectedVersionCode: Long) {
        val archive = archiveInfo(apk)
            ?: fail(UpdateError.UnexpectedApk("The downloaded file is not a readable APK"))
        if (archive.packageName != appContext.packageName) {
            fail(UpdateError.UnexpectedApk("The downloaded APK is a different app"))
        }
        val archiveVersionCode = PackageInfoCompat.getLongVersionCode(archive)
        if (archiveVersionCode != expectedVersionCode) {
            fail(
                UpdateError.UnexpectedApk(
                    "The downloaded APK is version $archiveVersionCode, the metadata published $expectedVersionCode",
                ),
            )
        }
        val installed = installedInfo() ?: fail(UpdateError.UnexpectedApk("This app is not installed"))
        val apkCertificates = ApkSigningCertificates.fingerprints(signingCertificates(archive))
        val installedCertificates = ApkSigningCertificates.fingerprints(signingCertificates(installed))
        if (!ApkSigningCertificates.matches(apkCertificates, installedCertificates, hasMultipleSigners(archive))) {
            // Deliberately opaque: the fingerprints themselves are of no use to the user and a mismatch
            // means the same thing either way — this APK is not ours.
            fail(UpdateError.SignatureMismatch)
        }
    }

    @Suppress("DEPRECATION")
    private fun archiveInfo(apk: File): PackageInfo? =
        packageManager.getPackageArchiveInfo(apk.absolutePath, ARCHIVE_FLAGS)

    @Suppress("DEPRECATION")
    private fun installedInfo(): PackageInfo? = try {
        packageManager.getPackageInfo(appContext.packageName, ARCHIVE_FLAGS)
    } catch (e: PackageManager.NameNotFoundException) {
        null
    }

    /** Raw certificate bytes of every signer, including a rotation history when the platform reports one. */
    @Suppress("DEPRECATION")
    private fun signingCertificates(info: PackageInfo): List<ByteArray> {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val signingInfo = info.signingInfo
            if (signingInfo != null) {
                val certificates = buildList {
                    signingInfo.apkContentsSigners?.forEach { add(it.toByteArray()) }
                    // Only meaningful for a single signer; with several, the history is not populated.
                    if (!signingInfo.hasMultipleSigners()) {
                        signingInfo.signingCertificateHistory?.forEach { add(it.toByteArray()) }
                    }
                }
                if (certificates.isNotEmpty()) return certificates
            }
            // Falls through to `signatures` on purpose: on the affected platform versions it is the
            // field that actually got filled in.
        }
        return info.signatures?.filterNotNull()?.map { it.toByteArray() }.orEmpty()
    }

    /** Several signers means every one of them has to be accounted for; see [ApkSigningCertificates.matches]. */
    @Suppress("DEPRECATION")
    private fun hasMultipleSigners(info: PackageInfo): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val signingInfo = info.signingInfo
            if (signingInfo != null) return signingInfo.hasMultipleSigners()
        }
        return (info.signatures?.filterNotNull()?.size ?: 0) > 1
    }

    private fun fail(error: UpdateError): Nothing = throw UpdateFailureException(error)

    companion object {
        /**
         * `GET_SIGNATURES` (deprecated since API 28) and `GET_SIGNING_CERTIFICATES` **together**: AOSP
         * only gathers certificates for an archive when the old flag is present, so the modern flag
         * alone returns nothing to compare on Android 9, 10 and 13.
         */
        @Suppress("DEPRECATION")
        const val ARCHIVE_FLAGS = PackageManager.GET_SIGNATURES or PackageManager.GET_SIGNING_CERTIFICATES
    }
}
