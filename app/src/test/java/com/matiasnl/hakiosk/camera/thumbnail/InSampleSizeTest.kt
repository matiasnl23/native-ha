package com.matiasnl.hakiosk.camera.thumbnail

import org.junit.Assert.assertEquals
import org.junit.Test

class InSampleSizeTest {

    @Test
    fun `4K frame for a small tile is subsampled but still covers it`() {
        // 3840x2160 / 8 = 480x270 >= 400x240; / 16 would be 240x135, too small.
        assertEquals(8, calculateInSampleSize(3840, 2160, 400, 240))
    }

    @Test
    fun `1080p frame for a portrait tile keeps the height covered`() {
        // Height is the limiting side: 1080 / 2 = 540 >= 400, 1080 / 4 = 270 < 400.
        assertEquals(2, calculateInSampleSize(1920, 1080, 300, 400))
    }

    @Test
    fun `image already smaller than the target is not subsampled`() {
        assertEquals(1, calculateInSampleSize(320, 240, 640, 480))
    }

    @Test
    fun `unknown target size decodes at full size`() {
        assertEquals(1, calculateInSampleSize(1920, 1080, 0, 0))
    }
}
