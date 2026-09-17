package com.matiasnl.hakiosk.data.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleaseMetadataParserTest {

    @Test
    fun `parses the published metadata format`() {
        val metadata = ReleaseMetadataParser.parse(REAL_METADATA).getOrThrow()

        assertEquals(10002L, metadata.versionCode)
        assertEquals("1.0.2", metadata.versionName)
        assertEquals(26, metadata.minSdk)
        assertEquals("deadbeef", metadata.commit)
        assertEquals("https://github.com/matiasnl23/native-ha/releases/tag/v1.0.2", metadata.releaseUrl)
        assertEquals(setOf("armeabi-v7a", "arm64-v8a", "universal"), metadata.apks.keys)
        assertEquals(
            ApkAsset("armeabi-v7a", "https://example.invalid/hakiosk-1.0.2-armeabi-v7a.apk", SHA_A, 18936062),
            metadata.apks["armeabi-v7a"],
        )
    }

    @Test
    fun `ignores unknown fields so the format can grow`() {
        val body = """
            {"versionCode":2,"versionName":"1.1","futureField":{"a":1},"minSdk":26,
             "apks":{"universal":{"url":"https://e.invalid/u.apk","sha256":"$SHA_A","sizeBytes":10,"extra":true}}}
        """.trimIndent()

        assertEquals(2L, ReleaseMetadataParser.parse(body).getOrThrow().versionCode)
    }

    @Test
    fun `uppercase sha256 is normalized to lowercase`() {
        val body = metadataWith(""""universal":{"url":"https://e.invalid/u.apk","sha256":"${SHA_A.uppercase()}","sizeBytes":10}""")

        assertEquals(SHA_A, ReleaseMetadataParser.parse(body).getOrThrow().apks.getValue("universal").sha256)
    }

    @Test
    fun `corrupt json is rejected`() {
        assertInvalid(ReleaseMetadataParser.parse("{not json"))
        assertInvalid(ReleaseMetadataParser.parse(""))
        assertInvalid(ReleaseMetadataParser.parse("[]"))
    }

    @Test
    fun `a versionCode of the wrong type is rejected instead of crashing`() {
        assertInvalid(ReleaseMetadataParser.parse(metadataWith(VALID_UNIVERSAL).replace(""""versionCode":10002""", """"versionCode":"1.0.2"""")))
    }

    @Test
    fun `metadata without versionCode, versionName or apks is rejected`() {
        assertInvalid(ReleaseMetadataParser.parse("""{"versionName":"1.0.2","apks":{$VALID_UNIVERSAL}}"""))
        assertInvalid(ReleaseMetadataParser.parse("""{"versionCode":10002,"apks":{$VALID_UNIVERSAL}}"""))
        assertInvalid(ReleaseMetadataParser.parse("""{"versionCode":10002,"versionName":"1.0.2"}"""))
        assertInvalid(ReleaseMetadataParser.parse("""{"versionCode":0,"versionName":"1.0.2","apks":{$VALID_UNIVERSAL}}"""))
    }

    @Test
    fun `an incomplete apk entry is dropped, not trusted`() {
        // The x86 entry is junk; the device's own ABI must still get its APK.
        val body = metadataWith(""""x86":{"…":"…"},"armeabi-v7a":{"url":"https://e.invalid/a.apk","sha256":"$SHA_A","sizeBytes":10},$VALID_UNIVERSAL""")

        val metadata = ReleaseMetadataParser.parse(body).getOrThrow()

        assertEquals(setOf("armeabi-v7a", "universal"), metadata.apks.keys)
    }

    @Test
    fun `entries with a bad sha256, a non-https url or no size are dropped`() {
        val body = metadataWith(
            """
            "arm64-v8a":{"url":"https://e.invalid/a.apk","sha256":"abc","sizeBytes":10},
            "x86":{"url":"http://e.invalid/x.apk","sha256":"$SHA_A","sizeBytes":10},
            "x86_64":{"url":"https://e.invalid/x64.apk","sha256":"$SHA_A"},
            "armeabi-v7a":{"url":"https://e.invalid/a.apk","sha256":"$SHA_A","sizeBytes":0},
            $VALID_UNIVERSAL
            """.trimIndent(),
        )

        assertEquals(setOf("universal"), ReleaseMetadataParser.parse(body).getOrThrow().apks.keys)
    }

    @Test
    fun `metadata whose every apk entry is unusable is rejected`() {
        assertInvalid(ReleaseMetadataParser.parse(metadataWith(""""universal":{"url":"https://e.invalid/u.apk","sha256":"nope","sizeBytes":10}""")))
    }

    @Test
    fun `a non-https release url is dropped but keeps the metadata usable`() {
        val body = metadataWith(VALID_UNIVERSAL).replace(
            """"releaseUrl":"https://github.com/matiasnl23/native-ha/releases/tag/v1.0.2"""",
            """"releaseUrl":"javascript:alert(1)"""",
        )

        assertNull(ReleaseMetadataParser.parse(body).getOrThrow().releaseUrl)
    }

    private fun assertInvalid(result: Result<ReleaseMetadata>) {
        assertTrue(result.toString(), result.exceptionOrNull() is InvalidReleaseMetadataException)
    }

    private fun metadataWith(apks: String) = """
        {"versionCode":10002,"versionName":"1.0.2","minSdk":26,"commit":"deadbeef",
         "releaseUrl":"https://github.com/matiasnl23/native-ha/releases/tag/v1.0.2",
         "apks":{$apks}}
    """.trimIndent()

    private companion object {
        const val SHA_A = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
        const val SHA_B = "fedcba9876543210fedcba9876543210fedcba9876543210fedcba9876543210"
        const val VALID_UNIVERSAL = """"universal":{"url":"https://e.invalid/u.apk","sha256":"$SHA_A","sizeBytes":60324507}"""

        val REAL_METADATA = """
            {
              "versionCode": 10002, "versionName": "1.0.2", "minSdk": 26, "commit": "deadbeef",
              "releaseUrl": "https://github.com/matiasnl23/native-ha/releases/tag/v1.0.2",
              "apks": {
                "armeabi-v7a": { "url": "https://example.invalid/hakiosk-1.0.2-armeabi-v7a.apk", "sha256": "$SHA_A", "sizeBytes": 18936062 },
                "arm64-v8a":   { "url": "https://example.invalid/hakiosk-1.0.2-arm64-v8a.apk", "sha256": "$SHA_B", "sizeBytes": 24413964 },
                "universal":   { "url": "https://example.invalid/hakiosk-1.0.2-universal.apk", "sha256": "$SHA_B", "sizeBytes": 60324507 }
              }
            }
        """.trimIndent()
    }
}
