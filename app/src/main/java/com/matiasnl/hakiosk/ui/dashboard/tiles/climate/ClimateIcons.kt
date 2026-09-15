package com.matiasnl.hakiosk.ui.dashboard.tiles.climate

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp
import com.matiasnl.hakiosk.data.ha.domain.HvacAction
import com.matiasnl.hakiosk.data.ha.domain.HvacMode

/**
 * Line icons for climate modes, drawn in-app because the project doesn't ship material-icons-extended.
 * Built once on first use; `Icon` tints them.
 */
object ClimateIcons {
    val Cool: ImageVector by lazy {
        lineIcon(
            "Cool",
            "M12 2.5v19M3.8 7.25l16.4 9.5M3.8 16.75l16.4-9.5",
            "M9.2 4.3L12 6l2.8-1.7M9.2 19.7L12 18l2.8 1.7",
        )
    }

    val Heat: ImageVector by lazy {
        lineIcon("Heat", "M12 2.8c0.6 3.6 5.2 5.6 5.2 10.4a5.2 5.2 0 0 1-10.4 0c0-2.6 1.5-3.9 2.3-5.8 0.9 1 1.3 2.1 1.3 3.2 1.7-1.7 2-4.5 1.6-7.8z")
    }

    val Auto: ImageVector by lazy {
        lineIcon("Auto", "M20 12a8 8 0 1 1-2.35-5.65M20 3.8V8h-4.2", "M9.3 15.5L12 8.5l2.7 7M10.2 13.3h3.6")
    }

    val Dry: ImageVector by lazy {
        lineIcon("Dry", "M12 3.5c3 4 5.5 6.9 5.5 10a5.5 5.5 0 0 1-11 0c0-3.1 2.5-6 5.5-10z")
    }

    val Fan: ImageVector by lazy {
        lineIcon(
            "Fan",
            "M12 12c-0.5-3.5 0.5-8 3.2-8 2.4 0 2.6 3.6-3.2 8z",
            "M12 12c3.3-1.3 7.8-0.6 8.3 2 0.5 2.3-3 3.2-8.3-2z",
            "M12 12c2.8 2.2 4.2 6.5 2.1 8.1-1.9 1.4-4.2-1.4-2.1-8.1z",
            "M12 12c-3.1 1.6-7.6 1.3-8.4-1.2-0.7-2.3 2.7-3.5 8.4 1.2z",
        )
    }

    val Off: ImageVector by lazy {
        lineIcon("Off", "M12 3.5v8M6.6 6.9a7.6 7.6 0 1 0 10.8 0")
    }
}

/** What the device is doing when it says so, else its mode. */
fun climateIcon(mode: HvacMode, action: HvacAction?): ImageVector = when {
    mode == HvacMode.OFF -> ClimateIcons.Off
    action == HvacAction.HEATING || action == HvacAction.PREHEATING -> ClimateIcons.Heat
    action == HvacAction.COOLING || action == HvacAction.DEFROSTING -> ClimateIcons.Cool
    action == HvacAction.DRYING -> ClimateIcons.Dry
    action == HvacAction.FAN -> ClimateIcons.Fan
    else -> when (mode) {
        HvacMode.HEAT -> ClimateIcons.Heat
        HvacMode.COOL -> ClimateIcons.Cool
        HvacMode.HEAT_COOL, HvacMode.AUTO -> ClimateIcons.Auto
        HvacMode.DRY -> ClimateIcons.Dry
        HvacMode.FAN_ONLY -> ClimateIcons.Fan
        HvacMode.OFF -> ClimateIcons.Off
    }
}

private fun lineIcon(name: String, vararg paths: String): ImageVector =
    ImageVector.Builder(
        name = name,
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        paths.forEach { path ->
            addPath(
                pathData = addPathNodes(path),
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 1.8f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }
    }.build()
