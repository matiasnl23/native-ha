package com.matiasnl.hakiosk.ui.dashboard.tiles.climate

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.matiasnl.hakiosk.data.ha.domain.HvacAction
import com.matiasnl.hakiosk.data.ha.domain.HvacMode
import com.matiasnl.hakiosk.ui.dashboard.tiles.TileSummary
import com.matiasnl.hakiosk.ui.dashboard.tiles.entityTileGestures
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Real taps on a quick-adjust climate tile with the dashboard's tile gestures: the − / + buttons move the
 * setpoint without opening the panel, and the rest of the tile still taps and long-presses as usual.
 */
@RunWith(AndroidJUnit4::class)
class ClimateQuickAdjustGesturesTest {

    @get:Rule
    val rule = createComposeRule()

    private val commits = mutableListOf<ClimateSetpoint>()
    private var clicks = 0
    private var longPresses = 0

    private val summary = TileSummary.Climate(
        HvacMode.COOL,
        HvacAction.COOLING,
        currentTemperature = 24.5,
        targetTemperature = 22.0,
        minTemperature = 16.0,
        maxTemperature = 30.0,
        temperatureStep = 1.0,
    )

    private fun setContent() {
        rule.setContent {
            val state = rememberClimateSetpointState(summary) { commits += it }
            Box(
                Modifier
                    .size(width = 225.dp, height = 138.dp)
                    .testTag("tile")
                    .entityTileGestures(
                        isActionable = true,
                        hasDetails = true,
                        onClick = { clicks++ },
                        onLongClick = { longPresses++ },
                    ),
            ) {
                ClimateQuickAdjustContent(
                    label = "Aire del living",
                    summary = summary,
                    wide = false,
                    labelStyle = MaterialTheme.typography.titleMedium,
                    setpoint = state,
                    onTurnOn = {},
                )
            }
        }
    }

    @Test
    fun plusButtonMovesTheSetpointWithoutOpeningThePanel() {
        setContent()

        rule.onNodeWithContentDescription("Subir Temperatura objetivo").performClick()
        rule.onNodeWithContentDescription("Subir Temperatura objetivo").performClick()
        rule.mainClock.advanceTimeBy(SETPOINT_COMMIT_DELAY_MILLIS + 100)
        rule.waitForIdle()

        assertEquals(listOf<ClimateSetpoint>(ClimateSetpoint.Single(24.0)), commits)
        assertEquals(0, clicks)
        assertEquals(0, longPresses)
    }

    @Test
    fun tappingOutsideTheButtonsStillOpensThePanel() {
        setContent()

        rule.onNodeWithTag("tile").performTouchInput {
            down(topLeft + androidx.compose.ui.geometry.Offset(30f, 30f))
            up()
        }
        rule.waitForIdle()

        assertEquals(1, clicks)
        assertEquals(emptyList<ClimateSetpoint>(), commits)
    }

    @Test
    fun longPressOutsideTheButtonsStillWorks() {
        setContent()

        var longPressMillis = 0L
        rule.onNodeWithTag("tile").performTouchInput {
            longPressMillis = viewConfiguration.longPressTimeoutMillis
            down(topLeft + androidx.compose.ui.geometry.Offset(30f, 30f))
        }
        rule.mainClock.advanceTimeBy(longPressMillis + 300)
        rule.onNodeWithTag("tile").performTouchInput { up() }
        rule.waitForIdle()

        assertEquals(1, longPresses)
        assertEquals(0, clicks)
    }
}
