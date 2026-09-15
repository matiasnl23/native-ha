package com.matiasnl.hakiosk.ui.dashboard.tiles.climate

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.matiasnl.hakiosk.ui.dashboard.tiles.TileSummary

/** Below this inner height the reading drops a size so the tile's three lines still fit. */
private val CompactTileHeight = 104.dp

/**
 * The climate tile's "Lectura" style: name and mode icon on top, the current temperature large, and a
 * detail line ("Enfriando · objetivo 22°"). On a wide tile the detail sits beside the reading.
 * Renders nothing while the mode is unknown (the caller falls back to the raw state).
 */
@Composable
fun ClimateTileContent(
    label: String,
    summary: TileSummary.Climate,
    wide: Boolean,
    labelStyle: TextStyle,
    modifier: Modifier = Modifier,
) {
    val mode = summary.hvacMode ?: return
    val reading = (summary.currentTemperature ?: summary.targetTemperature)?.let(::formatTemperature) ?: "—"
    val detail = climateReadingDetail(summary)
    BoxWithConstraints(modifier = modifier.fillMaxSize().padding(12.dp)) {
        val readingStyle = if (maxHeight < CompactTileHeight) {
            MaterialTheme.typography.headlineMedium
        } else {
            MaterialTheme.typography.displaySmall
        }
        Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = label,
                    style = labelStyle,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    imageVector = climateIcon(mode, summary.hvacAction),
                    contentDescription = null,
                    tint = summary.tint() ?: LocalContentColor.current,
                    modifier = Modifier.size(24.dp),
                )
            }
            if (wide) {
                Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text(text = reading, style = readingStyle, maxLines = 1)
                    detail?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f).padding(bottom = 4.dp),
                        )
                    }
                }
            } else {
                Column {
                    Text(text = reading, style = readingStyle, maxLines = 1)
                    detail?.let {
                        Text(text = it, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}
