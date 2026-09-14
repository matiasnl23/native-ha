package com.matiasnl.hakiosk.data.ha

import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.cert.CertPathValidatorException
import java.security.cert.CertificateException
import javax.net.ssl.SSLException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException

/** Human-readable description of a network failure. Never includes request headers (the token). */
internal object HaNetworkErrors {
    fun describe(error: Throwable): String {
        val detail = error.message ?: error.javaClass.simpleName
        return when {
            error is SSLPeerUnverifiedException -> "Hostname not verified: $detail"
            error is SSLHandshakeException && error.hasCause<CertificateException, CertPathValidatorException>() ->
                "Certificate not trusted: ${rootCause(error).message ?: detail}"
            error is SSLException -> "TLS error: $detail"
            error is UnknownHostException -> "Unknown host: $detail"
            error is ConnectException -> "Connection failed: $detail"
            error is SocketTimeoutException -> "Timed out: $detail"
            else -> detail
        }
    }

    private inline fun <reified A : Throwable, reified B : Throwable> Throwable.hasCause(): Boolean {
        var current: Throwable? = this
        while (current != null) {
            if (current is A || current is B) return true
            current = current.cause.takeIf { it !== current }
        }
        return false
    }

    private fun rootCause(error: Throwable): Throwable {
        var current = error
        while (true) current = current.cause?.takeIf { it !== current } ?: return current
    }
}
