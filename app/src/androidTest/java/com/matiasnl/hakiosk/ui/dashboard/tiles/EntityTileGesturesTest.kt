package com.matiasnl.hakiosk.ui.dashboard.tiles

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.matiasnl.hakiosk.ui.dashboard.tiles.light.rememberBrightnessSwipeState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Real gestures on a light tile's modifier chain inside a pager, as on the dashboard: a swipe changes
 * brightness without tapping, long-pressing or turning the page, and a long press still works.
 */
@RunWith(AndroidJUnit4::class)
class EntityTileGesturesTest {

    @get:Rule
    val rule = createComposeRule()

    private val commits = mutableListOf<Int>()
    private var clicks = 0
    private var longPresses = 0
    private lateinit var pagerState: PagerState

    private fun setContent(confirmedPercent: Float = 20f) {
        rule.setContent {
            pagerState = rememberPagerState { 2 }
            HorizontalPager(state = pagerState, modifier = Modifier.size(width = 400.dp, height = 300.dp)) { page ->
                Box(Modifier.fillMaxSize()) {
                    if (page == 0) {
                        val swipe = rememberBrightnessSwipeState(confirmedPercent) { commits += it }
                        Box(
                            Modifier
                                .size(200.dp)
                                .testTag("light")
                                .entityTileGestures(
                                    isActionable = true,
                                    hasDetails = true,
                                    onClick = { clicks++ },
                                    onLongClick = { longPresses++ },
                                    brightnessSwipe = swipe,
                                ),
                        )
                    }
                }
            }
        }
    }

    @Test
    fun swipeRightRaisesBrightnessWithoutClickLongPressOrPageChange() {
        setContent(confirmedPercent = 20f)

        rule.onNodeWithTag("light").performTouchInput {
            swipeRight(startX = left + width * 0.1f, endX = left + width * 0.6f, durationMillis = 300)
        }
        rule.waitForIdle()

        assertEquals(1, commits.size)
        assertTrue("expected about 70%, got ${commits.single()}", commits.single() in 60..80)
        assertEquals(0, clicks)
        assertEquals(0, longPresses)
        assertEquals(0, pagerState.currentPage)
    }

    @Test
    fun swipeLeftPastZeroCommitsZero() {
        setContent(confirmedPercent = 30f)

        rule.onNodeWithTag("light").performTouchInput {
            swipeLeft(startX = right - width * 0.1f, endX = left + width * 0.1f, durationMillis = 300)
        }
        rule.waitForIdle()

        assertEquals(listOf(0), commits)
        assertEquals(0, pagerState.currentPage)
    }

    @Test
    fun holdingStillLongPressesAndCommitsNothing() {
        setContent()

        var longPressMillis = 0L
        rule.onNodeWithTag("light").performTouchInput {
            longPressMillis = viewConfiguration.longPressTimeoutMillis
            down(center)
        }
        rule.mainClock.advanceTimeBy(longPressMillis + 300)
        rule.onNodeWithTag("light").performTouchInput { up() }
        rule.waitForIdle()

        assertEquals(1, longPresses)
        assertEquals(0, clicks)
        assertEquals(emptyList<Int>(), commits)
    }

    @Test
    fun movingAfterALongPressDoesNotChangeBrightness() {
        setContent()

        var longPressMillis = 0L
        rule.onNodeWithTag("light").performTouchInput {
            longPressMillis = viewConfiguration.longPressTimeoutMillis
            down(center)
        }
        rule.mainClock.advanceTimeBy(longPressMillis + 300)
        rule.onNodeWithTag("light").performTouchInput {
            repeat(20) { step -> moveTo(center.copy(x = centerX + width * 0.02f * (step + 1))) }
            up()
        }
        rule.waitForIdle()

        assertEquals(1, longPresses)
        assertEquals(emptyList<Int>(), commits)
    }

    @Test
    fun tapStillClicks() {
        setContent()

        rule.onNodeWithTag("light").performTouchInput {
            down(center)
            up()
        }
        rule.waitForIdle()

        assertEquals(1, clicks)
        assertEquals(emptyList<Int>(), commits)
    }
}
