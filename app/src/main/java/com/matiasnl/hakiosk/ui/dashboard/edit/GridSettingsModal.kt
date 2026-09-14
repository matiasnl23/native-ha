package com.matiasnl.hakiosk.ui.dashboard.edit

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.matiasnl.hakiosk.R
import com.matiasnl.hakiosk.data.dashboard.DashboardGrid
import com.matiasnl.hakiosk.ui.theme.HAKioskTheme

/**
 * Grid (columns/visible rows) settings for the view being edited, as a modal inside edit mode. Edits
 * a local [GridDraftController] with a live packing preview of [previewTiles]; nothing reaches the
 * working copy until [onApply], and dismissing (Cancelar, tap outside, back) discards the draft.
 */
@Composable
fun GridSettingsModal(
    grid: DashboardGrid,
    previewTiles: List<PreviewTile>,
    onApply: (DashboardGrid) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val controller = remember(grid, previewTiles) { GridDraftController(previewTiles, grid) }
    val draft by controller.state.collectAsState()
    val extraRows = draft.packing.totalRows - draft.rows

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            tonalElevation = 6.dp,
            modifier = modifier.widthIn(max = 480.dp).fillMaxWidth().padding(24.dp),
        ) {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()).padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(text = stringResource(R.string.grid_settings_title), style = MaterialTheme.typography.headlineSmall)

                SizeStepper(
                    label = stringResource(R.string.view_settings_columns),
                    value = draft.columns,
                    canDecrease = draft.canDecreaseColumns,
                    canIncrease = draft.canIncreaseColumns,
                    onChange = controller::onColumnsChange,
                )
                SizeStepper(
                    label = stringResource(R.string.view_settings_rows),
                    value = draft.rows,
                    canDecrease = draft.canDecreaseRows,
                    canIncrease = draft.canIncreaseRows,
                    onChange = controller::onRowsChange,
                )
                Text(
                    text = stringResource(R.string.view_settings_help),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Text(stringResource(R.string.view_settings_preview), style = MaterialTheme.typography.titleSmall)
                GridPreviewCanvas(grid = DashboardGrid(draft.columns, draft.rows), tiles = previewTiles, packing = draft.packing)
                if (extraRows > 0) {
                    Text(
                        text = pluralStringResource(R.plurals.view_settings_preview_scroll, extraRows, extraRows),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.grid_settings_cancel)) }
                    Button(onClick = { onApply(controller.currentGrid()) }) {
                        Text(stringResource(R.string.grid_settings_apply))
                    }
                }
            }
        }
    }
}

@Preview(showBackground = true, widthDp = 700, heightDp = 800)
@Composable
private fun GridSettingsModalPreview() {
    val tiles = listOf(PreviewTile(2, 2), PreviewTile(1, 1), PreviewTile(1, 1, isSpacer = true)) + List(6) { PreviewTile(1, 1) }
    HAKioskTheme {
        GridSettingsModal(
            grid = DashboardGrid(columns = 4, rows = 3),
            previewTiles = tiles,
            onApply = {},
            onDismiss = {},
        )
    }
}
