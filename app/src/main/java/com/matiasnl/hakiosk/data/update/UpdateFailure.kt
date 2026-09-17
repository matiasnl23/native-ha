package com.matiasnl.hakiosk.data.update

import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.MessageDigest
import javax.net.ssl.SSLException

/** Carries a typed [UpdateError] through a `Result` failure. */
class UpdateFailureException(val error: UpdateError) : Exception(error.toString())

/** The [UpdateError] behind a failed step, so every layer can fail with `Result` and stay typed. */
fun Throwable.toUpdateError(): UpdateError = when (this) {
    is UpdateFailureException -> error
    is InvalidReleaseMetadataException -> UpdateError.InvalidMetadata(message ?: "Invalid release metadata")
    else -> UpdateError.Network(UpdateNetworkErrors.describe(this))
}

internal fun updateFailure(error: UpdateError): Nothing = throw UpdateFailureException(error)

/** Short description of a network failure. Never includes request headers or the user's own URLs. */
internal object UpdateNetworkErrors {
    fun describe(error: Throwable): String {
        val detail = error.message ?: error.javaClass.simpleName
        return when (error) {
            is UnknownHostException -> "No connection (unknown host)"
            is ConnectException -> "Connection failed: $detail"
            is SocketTimeoutException -> "Timed out"
            is SSLException -> "TLS error: $detail"
            is IOException -> detail
            else -> detail
        }
    }
}

/** Lowercase hex SHA-256, the form used by `release-metadata.json` and by the signing certificate check. */
internal fun sha256Hex(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).toHexString()

internal fun ByteArray.toHexString(): String {
    val hex = StringBuilder(size * 2)
    for (byte in this) {
        val value = byte.toInt() and 0xFF
        hex.append(HEX_DIGITS[value ushr 4]).append(HEX_DIGITS[value and 0x0F])
    }
    return hex.toString()
}

private const val HEX_DIGITS = "0123456789abcdef"
