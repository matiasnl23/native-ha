package com.matiasnl.hakiosk.ui.remote

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalView

/**
 * Applies the app's own window brightness (no system permission needed; the kiosk app is always in
 * the foreground) whenever [screenOn] or [brightnessPercent] changes. While the screen is "off" (see
 * [ScreenOffOverlay]) the window is set to minimum brightness, restored once it turns back on.
 */
@Composable
fun WindowBrightnessEffect(screenOn: Boolean, brightnessPercent: Int) {
    val view = LocalView.current
    LaunchedEffect(screenOn, brightnessPercent) {
        val window = view.context.findActivity()?.window ?: return@LaunchedEffect
        val fraction = if (screenOn) (brightnessPercent / 100f) else 0f
        val params = window.attributes
        params.screenBrightness = fraction.coerceIn(0f, 1f)
        window.attributes = params
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
