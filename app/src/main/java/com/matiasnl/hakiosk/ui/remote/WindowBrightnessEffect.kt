package com.matiasnl.hakiosk.ui.remote

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalView

/**
 * Applies the app's own window brightness (no system permission needed; the kiosk app is always in
 * the foreground) whenever [screenOn] or [brightnessPercent] changes. While the screen is "off" (see
 * [ScreenOffOverlay]) the window is set to minimum brightness, restored once it turns back on. A null
 * [brightnessPercent] (never set remotely) follows the system brightness, including adaptive brightness.
 */
@Composable
fun WindowBrightnessEffect(screenOn: Boolean, brightnessPercent: Int?) {
    val view = LocalView.current
    LaunchedEffect(screenOn, brightnessPercent) {
        val window = view.context.findActivity()?.window ?: return@LaunchedEffect
        val params = window.attributes
        params.screenBrightness = when {
            !screenOn -> 0f
            brightnessPercent == null -> WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
            else -> (brightnessPercent / 100f).coerceIn(0f, 1f)
        }
        window.attributes = params
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
