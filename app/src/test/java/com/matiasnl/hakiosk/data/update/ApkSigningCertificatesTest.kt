package com.matiasnl.hakiosk.data.update

import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ApkSigningCertificatesTest {

    @Test
    fun `fingerprints are the lowercase hex sha256 of each certificate`() {
        val certificate = "release key".toByteArray()

        assertEquals(setOf(sha256Of(certificate)), ApkSigningCertificates.fingerprints(listOf(certificate)))
    }

    @Test
    fun `empty certificates are ignored`() {
        assertEquals(emptySet<String>(), ApkSigningCertificates.fingerprints(listOf(ByteArray(0))))
    }

    @Test
    fun `the same signing key matches`() {
        val certificates = fingerprintsOf("release key")

        assertTrue(ApkSigningCertificates.matches(certificates, certificates, apkHasMultipleSigners = false))
    }

    @Test
    fun `a different signing key does not match`() {
        assertFalse(
            ApkSigningCertificates.matches(
                fingerprintsOf("attacker key"),
                fingerprintsOf("release key"),
                apkHasMultipleSigners = false,
            ),
        )
    }

    @Test
    fun `a rotated key still matches through the shared certificate in the history`() {
        val apk = fingerprintsOf("new key", "release key")
        val installed = fingerprintsOf("release key")

        assertTrue(ApkSigningCertificates.matches(apk, installed, apkHasMultipleSigners = false))
    }

    @Test
    fun `with several signers every one of them must already be installed`() {
        val installed = fingerprintsOf("key one", "key two")

        assertTrue(
            ApkSigningCertificates.matches(
                fingerprintsOf("key one", "key two"),
                installed,
                apkHasMultipleSigners = true,
            ),
        )
    }

    @Test
    fun `with several signers a partial overlap is not enough`() {
        // Laxer than Android itself: it would let an APK signed by the release key plus an attacker's
        // key install over the app. Single-signer rotation still matches on one shared certificate.
        val installed = fingerprintsOf("release key")
        val apk = fingerprintsOf("release key", "attacker key")

        assertFalse(ApkSigningCertificates.matches(apk, installed, apkHasMultipleSigners = true))
        assertTrue(ApkSigningCertificates.matches(apk, installed, apkHasMultipleSigners = false))
    }

    @Test
    fun `no certificates on either side fails closed`() {
        val certificates = fingerprintsOf("release key")

        // Both branches: the empty check has to come before containsAll, which is true for an empty set.
        for (multipleSigners in listOf(false, true)) {
            assertFalse(ApkSigningCertificates.matches(emptySet(), certificates, multipleSigners))
            assertFalse(ApkSigningCertificates.matches(certificates, emptySet(), multipleSigners))
            assertFalse(ApkSigningCertificates.matches(emptySet(), emptySet(), multipleSigners))
        }
    }

    private fun fingerprintsOf(vararg keys: String): Set<String> =
        ApkSigningCertificates.fingerprints(keys.map { it.toByteArray() })

    private fun sha256Of(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
