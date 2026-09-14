package com.matiasnl.hakiosk.ui.dashboard.edit

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.matiasnl.hakiosk.R
import com.matiasnl.hakiosk.data.dashboard.DashboardGrid
import com.matiasnl.hakiosk.ui.dashboard.grid.GridMetrics
import com.matiasnl.hakiosk.ui.dashboard.grid.GridPacking

/**
 * A tile as drawn by [GridPreviewCanvas]: only what affects its look there, not its full domain
 * model. Shared by the grid-settings modal (previewing the whole view) and the edit-tile modal
 * (previewing the whole view with one tile [isHighlighted] as its size changes live).
 */
data class PreviewTile(
    val colSpan: Int,
    val rowSpan: Int,
    val isSpacer: Boolean = false,
    val isHighlighted: Boolean = false,
)

/**
 * The visible area of a dashboard view (shaped like the screen) with [tiles] packed into it as
 * rectangles. Rows beyond [grid]'s visible rows are cut off; the caller shows how many scroll.
 */
@Composable
fun GridPreviewCanvas(grid: DashboardGrid, tiles: List<PreviewTile>, packing: GridPacking, modifier: Modifier = Modifier) {
    val configuration = LocalConfiguration.current
    // Approximates the dashboard area's shape with the screen's (the top bar makes it slightly wider).
    val aspect = (configuration.screenWidthDp.toFloat() / configuration.screenHeightDp.coerceAtLeast(1))
        .coerceIn(0.4f, 2.5f)
    val background = MaterialTheme.colorScheme.surfaceContainerHighest
    val tileColor = MaterialTheme.colorScheme.primary
    val highlightColor = MaterialTheme.colorScheme.tertiary
    val spacerColor = MaterialTheme.colorScheme.outline
    val description = stringResource(R.string.view_settings_preview_description, grid.columns, grid.rows)

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(aspect)
            .semantics { contentDescription = description },
    ) {
        val corner = CornerRadius(6.dp.toPx())
        drawRoundRect(color = background, cornerRadius = corner)
        val metrics = GridMetrics(
            columns = packing.columns,
            rows = grid.rows,
            viewportWidth = size.width.toInt(),
            viewportHeight = size.height.toInt(),
            gutter = 4.dp.toPx(),
        )
        clipRect {
            val count = minOf(tiles.size, packing.placements.size)
            for (index in 0 until count) {
                val placement = packing.placements[index]
                if (!metrics.isVisible(placement, scrollOffset = 0)) continue
                val topLeft = Offset(metrics.left(placement).toFloat(), metrics.top(placement).toFloat())
                val tileSize = Size(metrics.width(placement).toFloat(), metrics.height(placement).toFloat())
                val tile = tiles[index]
                when {
                    tile.isHighlighted -> drawRoundRect(highlightColor, topLeft, tileSize, corner)
                    tile.isSpacer -> drawRoundRect(spacerColor, topLeft, tileSize, corner, style = Stroke(width = 1.dp.toPx()))
                    else -> drawRoundRect(tileColor.copy(alpha = 0.7f), topLeft, tileSize, corner)
                }
            }
        }
    }
}

/** A labelled −/+ stepper for an integer value, used for grid columns/rows and tile width/height. */
@Composable
fun SizeStepper(label: String, value: Int, canDecrease: Boolean, canIncrease: Boolean, onChange: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(text = label, style = MaterialTheme.typography.titleMedium, modifier = Modifier.width(120.dp))
        val decrease = stringResource(R.string.view_settings_decrease, label)
        val increase = stringResource(R.string.view_settings_increase, label)
        OutlinedButton(
            onClick = { onChange(-1) },
            enabled = canDecrease,
            modifier = Modifier.size(48.dp).semantics { contentDescription = decrease },
        ) { Text("−", style = MaterialTheme.typography.titleLarge) }
        Text(
            text = value.toString(),
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center,
            modifier = Modifier.width(48.dp),
        )
        OutlinedButton(
            onClick = { onChange(+1) },
            enabled = canIncrease,
            modifier = Modifier.size(48.dp).semantics { contentDescription = increase },
        ) { Text("+", style = MaterialTheme.typography.titleLarge) }
    }
}
