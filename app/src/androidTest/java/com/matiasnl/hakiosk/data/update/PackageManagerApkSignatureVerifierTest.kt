package com.matiasnl.hakiosk.data.update

import android.content.pm.PackageInfo
import android.os.Build
import androidx.core.content.pm.PackageInfoCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.matiasnl.hakiosk.data.update.android.PackageManagerApkSignatureVerifier
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The signature check on a real device, which is the only place the flags trap shows up: on Android 9,
 * 10 and 13 `getPackageArchiveInfo` collects no certificates unless `GET_SIGNATURES` is passed together
 * with `GET_SIGNING_CERTIFICATES`, and the production tablet is Android 10.
 *
 * Uses the installed app's own APK as the "downloaded" one, so it exercises the whole path — archive
 * parsing, the API 28 split between `signingInfo` and `signatures`, and the comparison against the
 * installed package — **without installing anything**.
 *
 * Run it (never `connectedAndroidTest`, which uninstalls the app and wipes the user's config):
 * `adb shell am instrument -w -e class com.matiasnl.hakiosk.data.update.PackageManagerApkSignatureVerifierTest com.matiasnl.hakiosk.test/androidx.test.runner.AndroidJUnitRunner`
 */
@RunWith(AndroidJUnit4::class)
class PackageManagerApkSignatureVerifierTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val packageManager = context.packageManager
    private val ownApk = File(context.applicationInfo.sourceDir)
    private val verifier = PackageManagerApkSignatureVerifier(context)

    private val ownVersionCode: Long
        get() = PackageInfoCompat.getLongVersionCode(packageManager.getPackageInfo(context.packageName, 0))

    @Test
    fun theAppsOwnApkPassesItsOwnSignatureCheck() {
        assertTrue("The app's own APK must be readable at ${ownApk.absolutePath}", ownApk.canRead())

        val result = verifier.verify(ownApk, ownVersionCode)

        assertEquals(null, result.exceptionOrNull()?.toUpdateError())
        assertTrue(result.isSuccess)
    }

    @Test
    fun bothFlagsTogetherActuallyCollectCertificates() {
        val archive = packageManager.getPackageArchiveInfo(
            ownApk.absolutePath,
            PackageManagerApkSignatureVerifier.ARCHIVE_FLAGS,
        )

        assertNotNull("getPackageArchiveInfo returned null for the app's own APK", archive)
        // This is the assertion that fails if someone "modernizes" ARCHIVE_FLAGS to drop GET_SIGNATURES.
        assertTrue(
            "No signing certificates collected: check that ARCHIVE_FLAGS keeps GET_SIGNATURES",
            certificatesOf(archive!!).isNotEmpty(),
        )
    }

    @Test
    fun theArchiveAndTheInstalledPackageReportTheSameSigner() {
        val archive = packageManager.getPackageArchiveInfo(
            ownApk.absolutePath,
            PackageManagerApkSignatureVerifier.ARCHIVE_FLAGS,
        )!!
        val installed = packageManager.getPackageInfo(
            context.packageName,
            PackageManagerApkSignatureVerifier.ARCHIVE_FLAGS,
        )

        val fromArchive = ApkSigningCertificates.fingerprints(certificatesOf(archive))
        val fromInstalled = ApkSigningCertificates.fingerprints(certificatesOf(installed))

        assertTrue("The installed package reported no certificates", fromInstalled.isNotEmpty())
        assertTrue(ApkSigningCertificates.matches(fromArchive, fromInstalled))
    }

    @Test
    fun anApkAtAnotherVersionIsRejected() {
        val result = verifier.verify(ownApk, ownVersionCode + 1)

        assertTrue(result.exceptionOrNull()?.toUpdateError() is UpdateError.UnexpectedApk)
    }

    @Test
    fun aFileThatIsNotAnApkIsRejected() {
        val notAnApk = File.createTempFile("not-an-apk", ".apk", context.cacheDir).apply {
            writeText("this is not a zip, let alone an APK")
        }
        try {
            val result = verifier.verify(notAnApk, ownVersionCode)

            assertTrue(result.exceptionOrNull()?.toUpdateError() is UpdateError.UnexpectedApk)
        } finally {
            notAnApk.delete()
        }
    }

    /** Same reading the verifier does, duplicated here so the test fails if that logic regresses. */
    @Suppress("DEPRECATION")
    private fun certificatesOf(info: PackageInfo): List<ByteArray> {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val signingInfo = info.signingInfo
            if (signingInfo != null) {
                val certificates = buildList {
                    signingInfo.apkContentsSigners?.forEach { add(it.toByteArray()) }
                    if (!signingInfo.hasMultipleSigners()) {
                        signingInfo.signingCertificateHistory?.forEach { add(it.toByteArray()) }
                    }
                }
                if (certificates.isNotEmpty()) return certificates
            }
        }
        return info.signatures?.filterNotNull()?.map { it.toByteArray() }.orEmpty()
    }
}
