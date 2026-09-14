package com.matiasnl.hakiosk.ui.viewsettings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.matiasnl.hakiosk.R
import com.matiasnl.hakiosk.data.dashboard.DashboardGrid
import com.matiasnl.hakiosk.ui.dashboard.grid.GridMetrics
import com.matiasnl.hakiosk.ui.dashboard.grid.GridPacker
import com.matiasnl.hakiosk.ui.dashboard.grid.GridPacking
import com.matiasnl.hakiosk.ui.theme.HAKioskTheme

@Composable
fun ViewSettingsScreen(viewModel: ViewSettingsViewModel, onClose: () -> Unit) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val currentOnClose by rememberUpdatedState(onClose)

    LaunchedEffect(uiState.isFinished) {
        if (uiState.isFinished) currentOnClose()
    }
    BackHandler(enabled = !uiState.isFinished) { viewModel.onCancel() }

    ViewSettingsContent(
        uiState = uiState,
        onColumnsChange = viewModel::onColumnsChange,
        onRowsChange = viewModel::onRowsChange,
        onDone = viewModel::onDone,
        onCancel = viewModel::onCancel,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ViewSettingsContent(
    uiState: ViewSettingsUiState,
    onColumnsChange: (Int) -> Unit,
    onRowsChange: (Int) -> Unit,
    onDone: () -> Unit,
    onCancel: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        if (uiState.viewName.isBlank()) {
                            stringResource(R.string.view_settings_title)
                        } else {
                            stringResource(R.string.view_settings_title_named, uiState.viewName)
                        },
                    )
                },
                actions = {
                    TextButton(onClick = onCancel, enabled = !uiState.isSaving) {
                        Text(stringResource(R.string.view_settings_cancel))
                    }
                    TextButton(onClick = onDone, enabled = !uiState.isSaving && !uiState.isLoading) {
                        Text(stringResource(R.string.view_settings_done))
                    }
                },
            )
        },
    ) { innerPadding ->
        if (!uiState.isLoading && !uiState.viewExists) {
            Text(
                text = stringResource(R.string.view_settings_not_found),
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(innerPadding).padding(24.dp),
            )
            return@Scaffold
        }
        BoxWithConstraints(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            val controls = @Composable { modifier: Modifier ->
                Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Stepper(
                        label = stringResource(R.string.view_settings_columns),
                        value = uiState.grid.columns,
                        canDecrease = uiState.canDecreaseColumns && !uiState.isLoading,
                        canIncrease = uiState.canIncreaseColumns && !uiState.isLoading,
                        onChange = onColumnsChange,
                    )
                    Stepper(
                        label = stringResource(R.string.view_settings_rows),
                        value = uiState.grid.rows,
                        canDecrease = uiState.canDecreaseRows && !uiState.isLoading,
                        canIncrease = uiState.canIncreaseRows && !uiState.isLoading,
                        onChange = onRowsChange,
                    )
                    Text(
                        text = stringResource(R.string.view_settings_help),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            val preview = @Composable { modifier: Modifier ->
                Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.view_settings_preview), style = MaterialTheme.typography.titleSmall)
                    GridPreview(grid = uiState.grid, tiles = uiState.previewTiles, packing = uiState.packing)
                    val extraRows = uiState.packing.totalRows - uiState.grid.rows
                    if (extraRows > 0) {
                        Text(
                            text = pluralStringResource(R.plurals.view_settings_preview_scroll, extraRows, extraRows),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            if (maxWidth >= 720.dp) {
                Row(
                    modifier = Modifier.fillMaxSize().padding(24.dp),
                    horizontalArrangement = Arrangement.spacedBy(32.dp),
                ) {
                    controls(Modifier.width(320.dp))
                    preview(Modifier.weight(1f).widthIn(max = 640.dp))
                }
            } else {
                Column(
                    modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(24.dp),
                ) {
                    controls(Modifier.fillMaxWidth())
                    preview(Modifier.fillMaxWidth())
                }
            }
        }
    }
}

@Composable
private fun Stepper(
    label: String,
    value: Int,
    canDecrease: Boolean,
    canIncrease: Boolean,
    onChange: (Int) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(text = label, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        val decrease = stringResource(R.string.view_settings_decrease, label)
        val increase = stringResource(R.string.view_settings_increase, label)
        OutlinedButton(
            onClick = { onChange(-1) },
            enabled = canDecrease,
            modifier = Modifier.size(56.dp).semantics { contentDescription = decrease },
        ) { Text("−", style = MaterialTheme.typography.titleLarge) }
        Text(
            text = value.toString(),
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center,
            modifier = Modifier.width(56.dp),
        )
        OutlinedButton(
            onClick = { onChange(+1) },
            enabled = canIncrease,
            modifier = Modifier.size(56.dp).semantics { contentDescription = increase },
        ) { Text("+", style = MaterialTheme.typography.titleLarge) }
    }
}

/**
 * The visible area of the dashboard (shaped like the screen) with the packed tiles as rectangles.
 * Rows beyond the visible ones are cut off; the caller shows how many scroll.
 */
@Composable
private fun GridPreview(grid: DashboardGrid, tiles: List<PreviewTile>, packing: GridPacking) {
    val configuration = LocalConfiguration.current
    // Approximates the dashboard area's shape with the screen's (the top bar makes it slightly wider).
    val aspect = (configuration.screenWidthDp.toFloat() / configuration.screenHeightDp.coerceAtLeast(1))
        .coerceIn(0.4f, 2.5f)
    val background = MaterialTheme.colorScheme.surfaceContainerHighest
    val tileColor = MaterialTheme.colorScheme.primary
    val spacerColor = MaterialTheme.colorScheme.outline
    val description = stringResource(R.string.view_settings_preview_description, grid.columns, grid.rows)

    Canvas(
        modifier = Modifier
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
                if (tiles[index].isSpacer) {
                    drawRoundRect(spacerColor, topLeft, tileSize, corner, style = Stroke(width = 1.dp.toPx()))
                } else {
                    drawRoundRect(tileColor.copy(alpha = 0.7f), topLeft, tileSize, corner)
                }
            }
        }
    }
}

@Preview(showBackground = true, widthDp = 1000, heightDp = 600)
@Composable
private fun ViewSettingsPreview() {
    val tiles = listOf(
        PreviewTile(2, 2, false),
        PreviewTile(1, 1, false),
        PreviewTile(1, 1, true),
        PreviewTile(1, 1, false),
        PreviewTile(2, 1, false),
    ) + List(10) { PreviewTile(1, 1, false) }
    val grid = DashboardGrid(columns = 4, rows = 3)
    HAKioskTheme {
        ViewSettingsContent(
            uiState = ViewSettingsUiState(
                isLoading = false,
                viewName = "Principal",
                grid = grid,
                previewTiles = tiles,
                packing = GridPacker().pack(grid.columns, tiles, { it.colSpan }, { it.rowSpan }),
            ),
            onColumnsChange = {},
            onRowsChange = {},
            onDone = {},
            onCancel = {},
        )
    }
}
