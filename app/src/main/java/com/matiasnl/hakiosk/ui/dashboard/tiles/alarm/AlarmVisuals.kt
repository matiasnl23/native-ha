package com.matiasnl.hakiosk.ui.dashboard.tiles.alarm

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.stringResource
import com.matiasnl.hakiosk.R
import com.matiasnl.hakiosk.data.ha.domain.AlarmPanelState

/** The visual family of an alarm state. */
enum class AlarmTone { DISARMED, ARMED, TRANSITION, TRIGGERED, UNKNOWN }

val AlarmPanelState.tone: AlarmTone
    get() = when (this) {
        AlarmPanelState.DISARMED -> AlarmTone.DISARMED
        AlarmPanelState.ARMED_HOME,
        AlarmPanelState.ARMED_AWAY,
        AlarmPanelState.ARMED_NIGHT,
        AlarmPanelState.ARMED_VACATION,
        AlarmPanelState.ARMED_CUSTOM_BYPASS,
        -> AlarmTone.ARMED
        AlarmPanelState.PENDING, AlarmPanelState.ARMING, AlarmPanelState.DISARMING -> AlarmTone.TRANSITION
        AlarmPanelState.TRIGGERED -> AlarmTone.TRIGGERED
        AlarmPanelState.UNAVAILABLE, AlarmPanelState.UNKNOWN -> AlarmTone.UNKNOWN
    }

@Composable
fun alarmStateText(state: AlarmPanelState): String = stringResource(
    when (state) {
        AlarmPanelState.DISARMED -> R.string.alarm_state_disarmed
        AlarmPanelState.ARMED_HOME -> R.string.alarm_state_armed_home
        AlarmPanelState.ARMED_AWAY -> R.string.alarm_state_armed_away
        AlarmPanelState.ARMED_NIGHT -> R.string.alarm_state_armed_night
        AlarmPanelState.ARMED_VACATION -> R.string.alarm_state_armed_vacation
        AlarmPanelState.ARMED_CUSTOM_BYPASS -> R.string.alarm_state_armed_custom_bypass
        AlarmPanelState.PENDING -> R.string.alarm_state_pending
        AlarmPanelState.ARMING -> R.string.alarm_state_arming
        AlarmPanelState.DISARMING -> R.string.alarm_state_disarming
        AlarmPanelState.TRIGGERED -> R.string.alarm_state_triggered
        AlarmPanelState.UNAVAILABLE -> R.string.dashboard_state_unavailable
        AlarmPanelState.UNKNOWN -> R.string.alarm_state_unknown
    },
)

@Immutable
data class TileColors(val container: Color, val content: Color)

private val DisarmedLight = TileColors(Color(0xFFD5EDD3), Color(0xFF10361A))
private val DisarmedDark = TileColors(Color(0xFF1E3A23), Color(0xFFBDE6BE))
private val TransitionLight = TileColors(Color(0xFFFFE19E), Color(0xFF3D2B00))
private val TransitionDark = TileColors(Color(0xFF4A3900), Color(0xFFFFDE96))

/** Disarmed green, armed red, pending/arming/disarming amber, triggered strong red; derived from the theme. */
@Composable
fun alarmColors(tone: AlarmTone): TileColors {
    val scheme = MaterialTheme.colorScheme
    val dark = scheme.surface.luminance() < 0.5f
    return when (tone) {
        AlarmTone.DISARMED -> if (dark) DisarmedDark else DisarmedLight
        AlarmTone.ARMED -> TileColors(scheme.errorContainer, scheme.onErrorContainer)
        AlarmTone.TRANSITION -> if (dark) TransitionDark else TransitionLight
        AlarmTone.TRIGGERED -> TileColors(scheme.error, scheme.onError)
        AlarmTone.UNKNOWN -> TileColors(scheme.surfaceVariant, scheme.onSurfaceVariant)
    }
}

/**
 * A light overlay that pulses over a triggered tile. Only composed while triggered, so the infinite
 * animation exists only then; the alpha is read in the layer, so a frame redraws without recomposing.
 */
@Composable
fun TriggeredPulse(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "alarmTriggeredPulse")
    val alpha = transition.animateFloat(
        initialValue = 0f,
        targetValue = 0.3f,
        animationSpec = infiniteRepeatable(tween(durationMillis = 700), RepeatMode.Reverse),
        label = "alarmTriggeredPulseAlpha",
    )
    Box(modifier = modifier.graphicsLayer { this.alpha = alpha.value }.background(Color.White))
}
