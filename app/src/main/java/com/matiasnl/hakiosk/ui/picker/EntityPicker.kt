package com.matiasnl.hakiosk.ui.picker

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.matiasnl.hakiosk.R
import com.matiasnl.hakiosk.data.ha.HaArea
import com.matiasnl.hakiosk.data.ha.HaFloor
import com.matiasnl.hakiosk.ui.theme.HAKioskTheme

/**
 * Self-contained entity search/filter picker: search field, collapsible domain/floor/area filter
 * chips, the truncation hint and the results list, each row with an "Agregar"/"Ya agregada" action.
 *
 * Owns its own scrollable [LazyColumn] for the results, so [modifier] should give it a bounded
 * height (e.g. `Modifier.fillMaxSize()`) and it must never be placed inside another scrollable
 * container (another `LazyColumn`, a `Column.verticalScroll`, etc.) — nesting same-orientation
 * scrollables breaks measurement/gestures. This is the version stage 3 should use inside a
 * `ModalBottomSheet`/`Dialog` for the "add tile" flow, sized to fill the sheet height.
 *
 * To embed the picker inside a screen that already owns a `LazyColumn` (as
 * [EditorScreen][com.matiasnl.hakiosk.ui.editor.EditorScreen] does), use the [entityPickerItems]
 * extension instead and skip this composable entirely.
 */
@Composable
fun EntityPicker(
    uiState: EntityPickerUiState,
    onQueryChange: (String) -> Unit,
    onDomainFilterChange: (String?) -> Unit,
    onFloorFilterChange: (String?) -> Unit,
    onAreaFilterChange: (String?) -> Unit,
    onPick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var filtersExpanded by rememberSaveable { mutableStateOf(false) }
    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        entityPickerItems(
            uiState = uiState,
            filtersExpanded = filtersExpanded,
            onFiltersExpandedChange = { filtersExpanded = it },
            onQueryChange = onQueryChange,
            onDomainFilterChange = onDomainFilterChange,
            onFloorFilterChange = onFloorFilterChange,
            onAreaFilterChange = onAreaFilterChange,
            onPick = onPick,
        )
    }
}

/**
 * The picker's search field, filter chip rows, truncation hint and results list, as items of an
 * existing [LazyColumn] — see [EntityPicker] for when to use this instead of that self-contained
 * composable. [filtersExpanded] is hoisted to the caller so it survives recomposition the same way
 * the rest of the caller's screen state does.
 */
fun LazyListScope.entityPickerItems(
    uiState: EntityPickerUiState,
    filtersExpanded: Boolean,
    onFiltersExpandedChange: (Boolean) -> Unit,
    onQueryChange: (String) -> Unit,
    onDomainFilterChange: (String?) -> Unit,
    onFloorFilterChange: (String?) -> Unit,
    onAreaFilterChange: (String?) -> Unit,
    onPick: (String) -> Unit,
) {
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
        TextButton(onClick = { onFiltersExpandedChange(!filtersExpanded) }) {
            Text(
                stringResource(
                    if (filtersExpanded) R.string.editor_filters_hide else R.string.editor_filters_show,
                ),
            )
        }
    }
    if (filtersExpanded) {
        item {
            DomainFilterRow(
                domains = uiState.domains,
                selected = uiState.domainFilter,
                onSelect = onDomainFilterChange,
            )
        }
        if (uiState.floors.isNotEmpty()) {
            item {
                FloorFilterRow(
                    floors = uiState.floors,
                    selected = uiState.floorFilter,
                    onSelect = onFloorFilterChange,
                )
            }
        }
        if (uiState.areas.isNotEmpty()) {
            item {
                AreaFilterRow(
                    areas = uiState.areas,
                    showNoArea = uiState.floorFilter == null,
                    selected = uiState.areaFilter,
                    onSelect = onAreaFilterChange,
                )
            }
        }
    }

    if (uiState.totalMatches > uiState.results.size) {
        item {
            Text(
                text = stringResource(
                    R.string.editor_results_truncated,
                    uiState.results.size,
                    uiState.totalMatches,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    if (uiState.results.isEmpty()) {
        item {
            Text(
                text = stringResource(R.string.editor_no_results),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    } else {
        items(uiState.results, key = { "picker-${it.entityId}" }) { row ->
            EntityPickerResultRow(row = row, onPick = { onPick(row.entityId) })
        }
    }
}

@Composable
private fun DomainFilterRow(domains: List<String>, selected: String?, onSelect: (String?) -> Unit) {
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        item(key = "__all_domains__") {
            FilterChip(
                selected = selected == null,
                onClick = { onSelect(null) },
                label = { ChipLabel(stringResource(R.string.editor_filter_all)) },
            )
        }
        items(domains, key = { it }) { domain ->
            FilterChip(
                selected = selected == domain,
                onClick = { onSelect(domain) },
                label = { ChipLabel(domain) },
            )
        }
    }
}

@Composable
private fun FloorFilterRow(floors: List<HaFloor>, selected: String?, onSelect: (String?) -> Unit) {
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        item(key = "__all_floors__") {
            FilterChip(
                selected = selected == null,
                onClick = { onSelect(null) },
                label = { ChipLabel(stringResource(R.string.editor_filter_all_floors)) },
            )
        }
        items(floors, key = { it.floorId }) { floor ->
            FilterChip(
                selected = selected == floor.floorId,
                onClick = { onSelect(floor.floorId) },
                label = { ChipLabel(floor.name) },
            )
        }
    }
}

@Composable
private fun AreaFilterRow(areas: List<HaArea>, showNoArea: Boolean, selected: String?, onSelect: (String?) -> Unit) {
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        item(key = "__all_areas__") {
            FilterChip(
                selected = selected == null,
                onClick = { onSelect(null) },
                label = { ChipLabel(stringResource(R.string.editor_filter_all_areas)) },
            )
        }
        if (showNoArea) {
            item(key = "__no_area__") {
                FilterChip(
                    selected = selected == EntityPickerState.NO_AREA_ID,
                    onClick = { onSelect(EntityPickerState.NO_AREA_ID) },
                    label = { ChipLabel(stringResource(R.string.editor_filter_no_area)) },
                )
            }
        }
        items(areas, key = { it.areaId }) { area ->
            FilterChip(
                selected = selected == area.areaId,
                onClick = { onSelect(area.areaId) },
                label = { ChipLabel(area.name) },
            )
        }
    }
}

/** Chip text that never wraps or gets squeezed vertically, even in a tight, scrollable row. */
@Composable
private fun ChipLabel(text: String) {
    Text(text = text, maxLines = 1, softWrap = false, overflow = TextOverflow.Clip)
}

@Composable
private fun EntityPickerResultRow(row: EntityPickerRow, onPick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.padding(end = 8.dp)) {
            Text(text = row.friendlyName, style = MaterialTheme.typography.bodyLarge)
            Text(text = row.entityId, style = MaterialTheme.typography.bodySmall)
            if (row.areaName != null) {
                val location = if (row.floorName != null) {
                    "${row.floorName} · ${row.areaName}"
                } else {
                    row.areaName
                }
                Text(
                    text = location,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (row.alreadyAdded) {
            Text(
                text = stringResource(R.string.editor_already_added),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 12.dp),
            )
        } else {
            Button(onClick = onPick) { Text(stringResource(R.string.editor_add)) }
        }
    }
}

private fun previewUiState() = EntityPickerUiState(
    results = listOf(
        EntityPickerRow(
            "light.kitchen", "Kitchen", "light", alreadyAdded = false,
            areaName = "Kitchen", floorName = "Ground floor",
        ),
        EntityPickerRow("light.living_room", "Living room", "light", alreadyAdded = true),
        EntityPickerRow("sensor.outdoor_temperature", "Outdoor temperature", "sensor", alreadyAdded = false),
    ),
    totalMatches = 3,
    domains = listOf("light", "sensor", "switch"),
    floors = listOf(HaFloor("ground", "Ground floor", 0), HaFloor("first", "First floor", 1)),
    areas = listOf(HaArea("kitchen", "Kitchen", "ground"), HaArea("living_room", "Living room", "ground")),
)

@Preview(showBackground = true, widthDp = 400, heightDp = 600)
@Composable
private fun EntityPickerPreview() {
    HAKioskTheme {
        EntityPicker(
            uiState = previewUiState(),
            onQueryChange = {},
            onDomainFilterChange = {},
            onFloorFilterChange = {},
            onAreaFilterChange = {},
            onPick = {},
        )
    }
}
