package com.matiasnl.hakiosk.data.update

import java.io.File

/**
 * Second half of the verification: the SHA-256 only proves the file is what the metadata published,
 * this proves it came from the release private key, which never left the developer's machine and the
 * GitHub secret store. Runs after the hash and before the install; a failure installs nothing.
 */
fun interface ApkSignatureVerifier {
    /**
     * Checks that [apk] is this same package, at [expectedVersionCode], signed with the same key as the
     * installed app. Fails with an [UpdateFailureException] carrying [UpdateError.SignatureMismatch] or
     * [UpdateError.UnexpectedApk].
     */
    fun verify(apk: File, expectedVersionCode: Long): Result<Unit>
}

/**
 * Signing certificate comparison, kept free of Android APIs so it is unit testable: the Android side
 * only has to hand over the raw certificate bytes of each side.
 */
object ApkSigningCertificates {

    /** Lowercase hex SHA-256 of each certificate, which is how signing keys are compared and reported. */
    fun fingerprints(certificates: List<ByteArray>): Set<String> =
        certificates.filter { it.isNotEmpty() }.map { sha256Hex(it) }.toSet()

    /**
     * True when the two sides share at least one signing certificate. An empty set on either side is
     * never a match: a missing certificate list must fail closed, never wave the APK through. Comparing
     * sets (rather than a single certificate) is what keeps a future key rotation installable, the same
     * way Android itself accepts an update signed by a rotated lineage.
     */
    fun matches(apkCertificates: Set<String>, installedCertificates: Set<String>): Boolean =
        apkCertificates.isNotEmpty() &&
            installedCertificates.isNotEmpty() &&
            apkCertificates.any { it in installedCertificates }
}
