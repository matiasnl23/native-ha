package com.matiasnl.hakiosk.ui.editor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.matiasnl.hakiosk.R
import com.matiasnl.hakiosk.ui.theme.HAKioskTheme

@Composable
fun EditorScreen(
    viewModel: EditorViewModel,
    onDone: () -> Unit,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    EditorContent(
        uiState = uiState,
        onQueryChange = viewModel::onQueryChange,
        onDomainFilterChange = viewModel::onDomainFilterChange,
        onAdd = viewModel::addTile,
        onRemove = viewModel::removeTile,
        onLabelChange = viewModel::setLabel,
        onMoveUp = viewModel::moveUp,
        onMoveDown = viewModel::moveDown,
        onSave = { viewModel.save(onDone) },
        onBack = onDone,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditorContent(
    uiState: EditorUiState,
    onQueryChange: (String) -> Unit,
    onDomainFilterChange: (String?) -> Unit,
    onAdd: (String) -> Unit,
    onRemove: (String) -> Unit,
    onLabelChange: (String, String) -> Unit,
    onMoveUp: (String) -> Unit,
    onMoveDown: (String) -> Unit,
    onSave: () -> Unit,
    onBack: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.editor_title)) },
                navigationIcon = {
                    TextButton(onClick = onBack) { Text(stringResource(R.string.setup_back)) }
                },
                actions = {
                    TextButton(onClick = onSave) { Text(stringResource(R.string.editor_save)) }
                },
            )
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                Text(
                    text = stringResource(R.string.editor_current_tiles),
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            if (uiState.currentTiles.isEmpty()) {
                item {
                    Text(
                        text = stringResource(R.string.editor_current_empty),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            } else {
                items(uiState.currentTiles, key = { "current-${it.entityId}" }) { tile ->
                    CurrentTileRow(
                        tile = tile,
                        onRemove = { onRemove(tile.entityId) },
                        onLabelChange = { onLabelChange(tile.entityId, it) },
                        onMoveUp = { onMoveUp(tile.entityId) },
                        onMoveDown = { onMoveDown(tile.entityId) },
                    )
                }
            }

            item { HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp)) }

            item {
                Text(
                    text = stringResource(R.string.editor_add_entities),
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            item {
                OutlinedTextField(
                    value = uiState.query,
                    onValueChange = onQueryChange,
                    placeholder = { Text(stringResource(R.string.editor_search_placeholder)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    FilterChip(
                        selected = uiState.domainFilter == null,
                        onClick = { onDomainFilterChange(null) },
                        label = { Text(stringResource(R.string.editor_filter_all)) },
                    )
                    uiState.domains.forEach { domain ->
                        FilterChip(
                            selected = uiState.domainFilter == domain,
                            onClick = { onDomainFilterChange(domain) },
                            label = { Text(domain) },
                        )
                    }
                }
            }

            if (uiState.availableEntities.isEmpty()) {
                item {
                    Text(
                        text = stringResource(R.string.editor_no_results),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            } else {
                items(uiState.availableEntities, key = { "available-${it.entityId}" }) { row ->
                    AvailableEntityRow(row = row, onAdd = { onAdd(row.entityId) })
                }
            }
        }
    }
}

@Composable
private fun CurrentTileRow(
    tile: EditorTileRow,
    onRemove: () -> Unit,
    onLabelChange: (String) -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(text = tile.friendlyName, style = MaterialTheme.typography.titleSmall)
            Text(text = tile.entityId, style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(
                value = tile.label.orEmpty(),
                onValueChange = onLabelChange,
                placeholder = { Text(stringResource(R.string.editor_label_placeholder)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onMoveUp) { Text(stringResource(R.string.editor_move_up)) }
                TextButton(onClick = onMoveDown) { Text(stringResource(R.string.editor_move_down)) }
                TextButton(onClick = onRemove) { Text(stringResource(R.string.editor_remove)) }
            }
        }
    }
}

@Composable
private fun AvailableEntityRow(row: EditorAvailableRow, onAdd: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.padding(end = 8.dp)) {
            Text(text = row.friendlyName, style = MaterialTheme.typography.bodyLarge)
            Text(text = row.entityId, style = MaterialTheme.typography.bodySmall)
        }
        if (row.alreadyAdded) {
            Text(
                text = stringResource(R.string.editor_already_added),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 12.dp),
            )
        } else {
            Button(onClick = onAdd) { Text(stringResource(R.string.editor_add)) }
        }
    }
}

@Preview(showBackground = true, widthDp = 400, heightDp = 800)
@Composable
private fun EditorScreenPreview() {
    HAKioskTheme {
        EditorContent(
            uiState = EditorUiState(
                currentTiles = listOf(
                    EditorTileRow("light.living_room", "Living room", label = null),
                    EditorTileRow("switch.coffee_maker", "Coffee maker", label = "Café"),
                ),
                availableEntities = listOf(
                    EditorAvailableRow("light.kitchen", "Kitchen", "light", alreadyAdded = false),
                    EditorAvailableRow("light.living_room", "Living room", "light", alreadyAdded = true),
                    EditorAvailableRow("sensor.outdoor_temperature", "Outdoor temperature", "sensor", alreadyAdded = false),
                ),
                domains = listOf("light", "sensor", "switch"),
            ),
            onQueryChange = {},
            onDomainFilterChange = {},
            onAdd = {},
            onRemove = {},
            onLabelChange = { _, _ -> },
            onMoveUp = {},
            onMoveDown = {},
            onSave = {},
            onBack = {},
        )
    }
}
