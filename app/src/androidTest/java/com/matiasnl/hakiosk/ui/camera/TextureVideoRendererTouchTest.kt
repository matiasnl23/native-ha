package com.matiasnl.hakiosk.ui.camera

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Card
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.matiasnl.hakiosk.camera.webrtc.TextureVideoRenderer
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The thumbnail video view must not swallow the tile's gestures: a tap opens the camera, a long press edits. */
@RunWith(AndroidJUnit4::class)
class TextureVideoRendererTouchTest {

    @get:Rule
    val rule = createComposeRule()

    @OptIn(ExperimentalFoundationApi::class)
    @Test
    fun tapsAndLongPressesOnTheVideoReachTheTile() {
        var clicks = 0
        var longClicks = 0
        rule.setContent {
            Card(
                modifier = Modifier
                    .size(200.dp)
                    .testTag("tile")
                    .combinedClickable(onClick = { clicks++ }, onLongClick = { longClicks++ }),
            ) {
                Box(Modifier.fillMaxSize()) {
                    AndroidView(factory = ::TextureVideoRenderer, modifier = Modifier.fillMaxSize())
                }
            }
        }

        rule.onNodeWithTag("tile").performTouchInput { click(center) }
        rule.onNodeWithTag("tile").performTouchInput { longClick(center) }
        rule.waitForIdle()

        assertEquals(1, clicks)
        assertEquals(1, longClicks)
    }
}
