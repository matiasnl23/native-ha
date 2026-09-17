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

        assertTrue(ApkSigningCertificates.matches(certificates, certificates))
    }

    @Test
    fun `a different signing key does not match`() {
        assertFalse(ApkSigningCertificates.matches(fingerprintsOf("attacker key"), fingerprintsOf("release key")))
    }

    @Test
    fun `a rotated key still matches through the shared certificate in the history`() {
        val apk = fingerprintsOf("new key", "release key")
        val installed = fingerprintsOf("release key")

        assertTrue(ApkSigningCertificates.matches(apk, installed))
    }

    @Test
    fun `no certificates on either side fails closed`() {
        val certificates = fingerprintsOf("release key")

        assertFalse(ApkSigningCertificates.matches(emptySet(), certificates))
        assertFalse(ApkSigningCertificates.matches(certificates, emptySet()))
        assertFalse(ApkSigningCertificates.matches(emptySet(), emptySet()))
    }

    private fun fingerprintsOf(vararg keys: String): Set<String> =
        ApkSigningCertificates.fingerprints(keys.map { it.toByteArray() })

    private fun sha256Of(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
