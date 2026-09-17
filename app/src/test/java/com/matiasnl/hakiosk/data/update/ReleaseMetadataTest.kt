package com.matiasnl.hakiosk.data.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleaseMetadataTest {

    @Test
    fun `picks the first supported abi in the device's own order`() {
        // A 64-bit tablet lists arm64 first and can run armeabi-v7a too: it must take the 64-bit APK.
        val metadata = metadata("armeabi-v7a", "arm64-v8a", "universal")

        val chosen = metadata.apkFor(listOf("arm64-v8a", "armeabi-v7a"))

        assertEquals("arm64-v8a", chosen?.abi)
    }

    @Test
    fun `an armeabi-v7a tablet gets the 32-bit APK, not the universal one`() {
        val metadata = metadata("armeabi-v7a", "arm64-v8a", "universal")

        assertEquals("armeabi-v7a", metadata.apkFor(listOf("armeabi-v7a"))?.abi)
    }

    @Test
    fun `falls back to universal when no listed abi is published`() {
        val metadata = metadata("arm64-v8a", "universal")

        assertEquals("universal", metadata.apkFor(listOf("x86_64", "x86"))?.abi)
    }

    @Test
    fun `without a match and without universal there is nothing to install`() {
        val metadata = metadata("arm64-v8a", "x86")

        assertNull(metadata.apkFor(listOf("armeabi-v7a")))
    }

    @Test
    fun `an empty abi list still finds the universal APK`() {
        assertEquals("universal", metadata("universal").apkFor(emptyList())?.abi)
    }

    @Test
    fun `only a strictly greater versionCode is an update`() {
        val metadata = metadata("universal", versionCode = 10002)

        assertTrue(metadata.isNewerThan(10001))
        assertFalse(metadata.isNewerThan(10002))
        assertFalse(metadata.isNewerThan(10003))
    }

    private fun metadata(vararg abis: String, versionCode: Long = 10002) = ReleaseMetadata(
        versionCode = versionCode,
        versionName = "1.0.2",
        minSdk = 26,
        commit = null,
        releaseUrl = null,
        apks = abis.associateWith { abi ->
            ApkAsset(abi, "https://e.invalid/$abi.apk", "0".repeat(64), 1_000)
        },
    )
}
