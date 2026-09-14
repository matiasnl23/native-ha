package com.matiasnl.hakiosk.data.ha

import org.junit.Assert.assertEquals
import org.junit.Test
import java.net.UnknownHostException
import java.security.cert.CertPathValidatorException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException

class HaNetworkErrorsTest {
    @Test
    fun untrustedCertificateIsDescribed() {
        val error = SSLHandshakeException("handshake failed").apply {
            initCause(CertPathValidatorException("Trust anchor for certification path not found."))
        }
        assertEquals(
            "Certificate not trusted: Trust anchor for certification path not found.",
            HaNetworkErrors.describe(error),
        )
    }

    @Test
    fun hostnameMismatchIsDescribed() {
        assertEquals(
            "Hostname not verified: Hostname ha.local not verified",
            HaNetworkErrors.describe(SSLPeerUnverifiedException("Hostname ha.local not verified")),
        )
    }

    @Test
    fun unknownHostIsDescribed() {
        assertEquals("Unknown host: ha.invalid", HaNetworkErrors.describe(UnknownHostException("ha.invalid")))
    }
}
