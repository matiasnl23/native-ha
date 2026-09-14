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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.matiasnl.hakiosk.R
import com.matiasnl.hakiosk.data.dashboard.CELL_RANGE
import com.matiasnl.hakiosk.data.dashboard.DashboardGrid
import com.matiasnl.hakiosk.ui.dashboard.grid.GridPacker
import com.matiasnl.hakiosk.ui.theme.HAKioskTheme

/**
 * Edits one tile of the working copy: label (entity/link tiles only), width/height with a live
 * preview of the whole view, and a "Quitar" button. Applying (or removing) writes to the working
 * copy through [onApply]/[onRemove]; dismissing the dialog (tap outside, back, Cancelar) discards
 * whatever was changed in this modal's own local state.
 *
 * [domainSections] is the extension point stage 6 hooks into for per-domain controls (e.g. a light's
 * brightness/color): each one is rendered as its own section, in order, between the label field and
 * the size steppers, without this modal needing to know what they are.
 */
@Composable
fun EditTileModal(
    title: String,
    showLabelField: Boolean,
    initialLabel: String,
    labelPlaceholder: String,
    grid: DashboardGrid,
    tiles: List<PreviewTile>,
    editingIndex: Int,
    onApply: (label: String?, colSpan: Int, rowSpan: Int) -> Unit,
    onRemove: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    domainSections: List<@Composable () -> Unit> = emptyList(),
) {
    val editingTile = tiles.getOrNull(editingIndex) ?: return
    var label by rememberSaveable(editingIndex) { mutableStateOf(initialLabel) }
    var colSpan by rememberSaveable(editingIndex) { mutableStateOf(editingTile.colSpan) }
    var rowSpan by rememberSaveable(editingIndex) { mutableStateOf(editingTile.rowSpan) }
    val maxColSpan = grid.columns.coerceIn(CELL_RANGE)

    val previewTiles = tiles.mapIndexed { index, tile ->
        if (index == editingIndex) tile.copy(colSpan = colSpan, rowSpan = rowSpan, isHighlighted = true) else tile
    }
    val packer = remember { GridPacker() }
    val packing = remember(grid.columns, previewTiles) {
        packer.pack(grid.columns, previewTiles, { it.colSpan }, { it.rowSpan })
    }

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
                Text(text = title, style = MaterialTheme.typography.headlineSmall)

                if (showLabelField) {
                    OutlinedTextField(
                        value = label,
                        onValueChange = { label = it },
                        label = { Text(stringResource(R.string.edit_tile_label)) },
                        placeholder = { Text(labelPlaceholder) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                domainSections.forEach { section ->
                    HorizontalDivider()
                    section()
                }

                HorizontalDivider()

                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.edit_tile_size), style = MaterialTheme.typography.titleSmall)
                    SizeStepper(
                        label = stringResource(R.string.edit_tile_width),
                        value = colSpan,
                        canDecrease = colSpan > CELL_RANGE.first,
                        canIncrease = colSpan < maxColSpan,
                        onChange = { delta -> colSpan = (colSpan + delta).coerceIn(CELL_RANGE.first, maxColSpan) },
                    )
                    SizeStepper(
                        label = stringResource(R.string.edit_tile_height),
                        value = rowSpan,
                        canDecrease = rowSpan > CELL_RANGE.first,
                        canIncrease = rowSpan < CELL_RANGE.last,
                        onChange = { delta -> rowSpan = (rowSpan + delta).coerceIn(CELL_RANGE) },
                    )
                    Text(
                        text = stringResource(R.string.edit_tile_preview),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    GridPreviewCanvas(grid = grid, tiles = previewTiles, packing = packing)
                }

                HorizontalDivider()

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    TextButton(onClick = onRemove) { Text(stringResource(R.string.edit_tile_remove)) }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = onDismiss) { Text(stringResource(R.string.edit_tile_cancel)) }
                        Button(onClick = { onApply(label.trim().ifEmpty { null }, colSpan, rowSpan) }) {
                            Text(stringResource(R.string.edit_tile_apply))
                        }
                    }
                }
            }
        }
    }
}

@Preview(showBackground = true, widthDp = 700, heightDp = 800)
@Composable
private fun EditTileModalPreview() {
    val tiles = listOf(
        PreviewTile(2, 2),
        PreviewTile(1, 1),
        PreviewTile(1, 1, isSpacer = true),
        PreviewTile(2, 1),
    )
    HAKioskTheme {
        EditTileModal(
            title = "Living room",
            showLabelField = true,
            initialLabel = "",
            labelPlaceholder = "Living room",
            grid = DashboardGrid(columns = 4, rows = 3),
            tiles = tiles,
            editingIndex = 0,
            onApply = { _, _, _ -> },
            onRemove = {},
            onDismiss = {},
        )
    }
}
