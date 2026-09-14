package com.matiasnl.hakiosk.ui.dashboard.edit

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.matiasnl.hakiosk.R
import com.matiasnl.hakiosk.ui.picker.EntityPicker
import com.matiasnl.hakiosk.ui.picker.EntityPickerUiState
import com.matiasnl.hakiosk.ui.theme.HAKioskTheme

private enum class AddTileOption { Entity, Spacer, Link }

/**
 * Add-tile modal opened from the "＋" tile: pick an entity (via the shared [EntityPicker]), add a
 * spacer, or add a link to another view. Every successful pick both applies the add (through one of
 * the on* callbacks) and closes the modal.
 */
@Composable
fun AddTileModal(
    pickerState: EntityPickerUiState,
    onQueryChange: (String) -> Unit,
    onDomainFilterChange: (String?) -> Unit,
    onFloorFilterChange: (String?) -> Unit,
    onAreaFilterChange: (String?) -> Unit,
    onPickEntity: (entityId: String) -> Unit,
    onPickSpacer: () -> Unit,
    linkTargets: List<LinkTargetOption>,
    onPickLink: (viewId: String) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var option by rememberSaveable { mutableStateOf(AddTileOption.Entity) }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            tonalElevation = 6.dp,
            modifier = modifier.fillMaxWidth(0.9f).fillMaxHeight(0.85f).padding(24.dp),
        ) {
            Column(modifier = Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(text = stringResource(R.string.add_tile_title), style = MaterialTheme.typography.headlineSmall)

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = option == AddTileOption.Entity,
                        onClick = { option = AddTileOption.Entity },
                        label = { Text(stringResource(R.string.add_tile_option_entity)) },
                    )
                    FilterChip(
                        selected = option == AddTileOption.Spacer,
                        onClick = { option = AddTileOption.Spacer },
                        label = { Text(stringResource(R.string.add_tile_option_spacer)) },
                    )
                    FilterChip(
                        selected = option == AddTileOption.Link,
                        onClick = { option = AddTileOption.Link },
                        label = { Text(stringResource(R.string.add_tile_option_link)) },
                    )
                }

                Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    when (option) {
                        AddTileOption.Entity -> EntityPicker(
                            uiState = pickerState,
                            onQueryChange = onQueryChange,
                            onDomainFilterChange = onDomainFilterChange,
                            onFloorFilterChange = onFloorFilterChange,
                            onAreaFilterChange = onAreaFilterChange,
                            onPick = { entityId ->
                                onPickEntity(entityId)
                                onDismiss()
                            },
                            modifier = Modifier.fillMaxSize(),
                        )
                        AddTileOption.Spacer -> SpacerOption(onAdd = { onPickSpacer(); onDismiss() })
                        AddTileOption.Link -> LinkOption(
                            targets = linkTargets,
                            onPick = { viewId -> onPickLink(viewId); onDismiss() },
                        )
                    }
                }

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.add_tile_cancel)) }
                }
            }
        }
    }
}

@Composable
private fun SpacerOption(onAdd: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
    ) {
        Text(text = stringResource(R.string.add_tile_spacer_hint), style = MaterialTheme.typography.bodyMedium)
        Button(onClick = onAdd) { Text(stringResource(R.string.add_tile_spacer_action)) }
    }
}

@Composable
private fun LinkOption(targets: List<LinkTargetOption>, onPick: (String) -> Unit, modifier: Modifier = Modifier) {
    if (targets.isEmpty()) {
        Text(
            text = stringResource(R.string.add_tile_link_empty),
            style = MaterialTheme.typography.bodyMedium,
            modifier = modifier.fillMaxSize().padding(top = 8.dp),
        )
        return
    }
    LazyColumn(modifier = modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(targets, key = { it.viewId }) { target ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(text = target.name, style = MaterialTheme.typography.bodyLarge)
                    TextButton(onClick = { onPick(target.viewId) }) {
                        Text(stringResource(R.string.add_tile_option_link))
                    }
                }
            }
        }
    }
}

@Preview(showBackground = true, widthDp = 700, heightDp = 800)
@Composable
private fun AddTileModalPreview() {
    HAKioskTheme {
        AddTileModal(
            pickerState = EntityPickerUiState(),
            onQueryChange = {},
            onDomainFilterChange = {},
            onFloorFilterChange = {},
            onAreaFilterChange = {},
            onPickEntity = {},
            onPickSpacer = {},
            linkTargets = listOf(LinkTargetOption("v2", "Planta alta")),
            onPickLink = {},
            onDismiss = {},
        )
    }
}
