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
     * Whether the APK may be installed over the running app.
     *
     * An empty set on either side is never a match: a missing certificate list must fail closed, never
     * wave the APK through.
     *
     * With a single signer, sharing one certificate is enough, which is what keeps a future key
     * rotation installable — the same way Android accepts an update signed by a rotated lineage.
     * With [apkHasMultipleSigners], **every** signer of the APK must already be among the installed
     * ones: Android requires the whole set to match there, and accepting one shared certificate would
     * make this check laxer than the platform's.
     */
    fun matches(
        apkCertificates: Set<String>,
        installedCertificates: Set<String>,
        // No default on purpose: the single-signer branch is the lax one, and defaulting to it would let
        // a future caller weaken the check by forgetting an argument.
        apkHasMultipleSigners: Boolean,
    ): Boolean = when {
        apkCertificates.isEmpty() || installedCertificates.isEmpty() -> false
        apkHasMultipleSigners -> installedCertificates.containsAll(apkCertificates)
        else -> apkCertificates.any { it in installedCertificates }
    }
}
