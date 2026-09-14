package com.matiasnl.hakiosk.ui.remote

import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.clickable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import com.matiasnl.hakiosk.R
import com.matiasnl.hakiosk.ui.theme.HAKioskTheme

/**
 * Full-screen black overlay standing in for a real "screen off" until Device Owner is available (see
 * README.md). Drawn above everything else (dashboard, camera view, dialogs); a touch anywhere on it
 * calls [onTouch] and, being on top, consumes the touch so it never also reaches whatever is under it.
 */
@Composable
fun ScreenOffOverlay(onTouch: () -> Unit, modifier: Modifier = Modifier) {
    val description = stringResource(R.string.remote_screen_overlay_description)
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onTouch,
            )
            .semantics { contentDescription = description },
    )
}

@Preview(showBackground = true, widthDp = 800, heightDp = 480)
@Composable
private fun ScreenOffOverlayPreview() {
    HAKioskTheme {
        ScreenOffOverlay(onTouch = {})
    }
}
