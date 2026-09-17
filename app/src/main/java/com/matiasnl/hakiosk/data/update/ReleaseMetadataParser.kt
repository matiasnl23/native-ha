package com.matiasnl.hakiosk.data.update

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** The metadata was fetched but can't be used. Its message is safe to show: it never echoes the body. */
class InvalidReleaseMetadataException(message: String) : Exception(message)

/**
 * Parses `release-metadata.json`. Deliberately lenient about what it doesn't need and strict about what
 * it does: unknown fields are ignored (the format can grow without breaking installed tablets) and an
 * individual APK entry that is incomplete or malformed is dropped, so one broken entry can't stop a
 * device whose own ABI is fine. The top-level fields the flow depends on are required, and a metadata
 * without a single usable APK is rejected rather than half-trusted.
 */
object ReleaseMetadataParser {
    private val json = Json { ignoreUnknownKeys = true }

    private val SHA256_HEX = Regex("[0-9a-fA-F]{64}")

    fun parse(body: String): Result<ReleaseMetadata> = try {
        Result.success(validate(json.decodeFromString<MetadataDto>(body)))
    } catch (e: InvalidReleaseMetadataException) {
        Result.failure(e)
    } catch (e: IllegalArgumentException) {
        // SerializationException extends IllegalArgumentException: covers malformed JSON and wrong types.
        Result.failure(InvalidReleaseMetadataException("Malformed release metadata"))
    }

    private fun validate(dto: MetadataDto): ReleaseMetadata {
        val versionCode = dto.versionCode?.takeIf { it > 0 } ?: fail("Missing versionCode")
        val versionName = dto.versionName?.trim()?.takeIf { it.isNotEmpty() } ?: fail("Missing versionName")
        val apks = dto.apks.orEmpty()
            .mapNotNull { (abi, asset) -> asset.toAsset(abi.trim()) }
            .associateBy { it.abi }
        if (apks.isEmpty()) fail("No usable APK in the release metadata")
        return ReleaseMetadata(
            versionCode = versionCode,
            versionName = versionName,
            minSdk = dto.minSdk ?: 0,
            commit = dto.commit?.takeIf { it.isNotBlank() },
            releaseUrl = dto.releaseUrl?.takeIf { it.startsWith(HTTPS_PREFIX) },
            apks = apks,
        )
    }

    /** Null drops the entry: it could only lead to downloading something that can't be verified. */
    private fun ApkDto.toAsset(abi: String): ApkAsset? {
        if (abi.isEmpty()) return null
        val url = url?.trim()?.takeIf { it.startsWith(HTTPS_PREFIX) } ?: return null
        val sha256 = sha256?.trim()?.takeIf { SHA256_HEX.matches(it) }?.lowercase() ?: return null
        val size = sizeBytes?.takeIf { it > 0 } ?: return null
        return ApkAsset(abi = abi, url = url, sha256 = sha256, sizeBytes = size)
    }

    private fun fail(message: String): Nothing = throw InvalidReleaseMetadataException(message)

    private const val HTTPS_PREFIX = "https://"

    /** Every field is nullable on purpose: a missing or mistyped one must fail validation, not parsing. */
    @Serializable
    private class MetadataDto(
        val versionCode: Long? = null,
        val versionName: String? = null,
        val minSdk: Int? = null,
        val commit: String? = null,
        val releaseUrl: String? = null,
        val apks: Map<String, ApkDto>? = null,
    )

    @Serializable
    private class ApkDto(
        val url: String? = null,
        val sha256: String? = null,
        val sizeBytes: Long? = null,
    )
}
