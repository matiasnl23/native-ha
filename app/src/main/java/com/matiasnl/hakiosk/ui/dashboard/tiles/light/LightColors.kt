package com.matiasnl.hakiosk.ui.dashboard.tiles.light

import androidx.compose.ui.graphics.Color
import kotlin.math.ln
import kotlin.math.pow

/** Hue stops for a rainbow track: red → yellow → green → cyan → blue → magenta → red. */
internal val RainbowColors: List<Color> = List(7) { index -> Color.hsv((index * 60f) % 360f, 1f, 1f) }

/**
 * Approximate display color of black-body light at [kelvin] (Tanner Helland's fit), good enough for a
 * warm→cool slider track. Computed only when a panel's Kelvin range changes, never per frame.
 */
internal fun kelvinToColor(kelvin: Int): Color {
    val t = kelvin.coerceIn(1000, 40000) / 100.0
    val red = if (t <= 66) 255.0 else 329.698727446 * (t - 60).pow(-0.1332047592)
    val green = if (t <= 66) 99.4708025861 * ln(t) - 161.1195681661 else 288.1221695283 * (t - 60).pow(-0.0755148492)
    val blue = when {
        t >= 66 -> 255.0
        t <= 19 -> 0.0
        else -> 138.5177312231 * ln(t - 10) - 305.0447927307
    }
    return Color(red.channel(), green.channel(), blue.channel())
}

private fun Double.channel(): Int = coerceIn(0.0, 255.0).toInt()
