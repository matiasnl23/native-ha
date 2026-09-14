package com.matiasnl.hakiosk.ui.dashboard.edit

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.matiasnl.hakiosk.R
import com.matiasnl.hakiosk.data.dashboard.INACTIVITY_RETURN_OPTIONS
import com.matiasnl.hakiosk.ui.theme.HAKioskTheme

/** One row of the views modal. */
data class ViewListItem(val viewId: String, val name: String)

/** First of `nameFor(count + 1)`, `nameFor(count + 2)`, … not already taken, so a fresh default never repeats a name. */
internal fun nextDefaultViewName(existingNames: List<String>, nameFor: (Int) -> String): String {
    var number = existingNames.size + 1
    while (nameFor(number) in existingNames) number++
    return nameFor(number)
}

/**
 * Manages the views of the edit-mode working copy: add (appended, then edited), rename, delete (with
 * a confirmation, never the last view) and reorder with up/down buttons. Every action applies to the
 * working copy at once, so the dashboard behind follows along; nothing is persisted until Listo and
 * Cancelar discards it all. Tapping a view's name edits that view.
 */
@Composable
fun ViewsModal(
    views: List<ViewListItem>,
    editingViewId: String?,
    onAddView: (name: String) -> Unit,
    onRenameView: (viewId: String, name: String) -> Unit,
    onRemoveView: (viewId: String) -> Unit,
    onMoveView: (fromIndex: Int, toIndex: Int) -> Unit,
    onSelectView: (viewId: String) -> Unit,
    inactivityReturnMinutes: Int,
    onInactivityReturnChange: (minutes: Int) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var renamingViewId by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingDelete by remember { mutableStateOf<ViewListItem?>(null) }
    val context = LocalContext.current
    val defaultName = nextDefaultViewName(views.map { it.name }) { number ->
        context.getString(R.string.views_default_name, number)
    }
    // Resets to the next default name whenever a view is added or removed.
    var newName by rememberSaveable(views.size) { mutableStateOf(defaultName) }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            tonalElevation = 6.dp,
            modifier = modifier.widthIn(max = 560.dp).fillMaxWidth().padding(24.dp),
        ) {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()).padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(text = stringResource(R.string.views_title), style = MaterialTheme.typography.headlineSmall)
                Text(
                    text = stringResource(R.string.views_help),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                views.forEachIndexed { index, view ->
                    if (view.viewId == renamingViewId) {
                        RenameRow(
                            initialName = view.name,
                            onSave = { name ->
                                onRenameView(view.viewId, name)
                                renamingViewId = null
                            },
                            onCancel = { renamingViewId = null },
                        )
                    } else {
                        ViewRow(
                            view = view,
                            isEditing = view.viewId == editingViewId,
                            canMoveUp = index > 0,
                            canMoveDown = index < views.lastIndex,
                            canDelete = views.size > 1,
                            onSelect = { onSelectView(view.viewId) },
                            onMoveUp = { onMoveView(index, index - 1) },
                            onMoveDown = { onMoveView(index, index + 1) },
                            onRename = { renamingViewId = view.viewId },
                            onDelete = { pendingDelete = view },
                        )
                    }
                }
                if (views.size == 1) {
                    Text(
                        text = stringResource(R.string.views_last_view_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                HorizontalDivider()

                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = newName,
                        onValueChange = { newName = it },
                        label = { Text(stringResource(R.string.views_new_label)) },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    Button(onClick = { onAddView(newName.trim()) }, enabled = newName.isNotBlank()) {
                        Text(stringResource(R.string.views_add))
                    }
                }

                HorizontalDivider()

                InactivityReturnSection(selectedMinutes = inactivityReturnMinutes, onChange = onInactivityReturnChange)

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.views_close)) }
                }
            }
        }
    }

    pendingDelete?.let { view ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.views_delete_title, view.name)) },
            text = { Text(stringResource(R.string.views_delete_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        onRemoveView(view.viewId)
                        if (renamingViewId == view.viewId) renamingViewId = null
                        pendingDelete = null
                    },
                ) {
                    Text(stringResource(R.string.views_delete_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text(stringResource(R.string.views_delete_keep)) }
            },
        )
    }
}

@Composable
private fun ViewRow(
    view: ViewListItem,
    isEditing: Boolean,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    canDelete: Boolean,
    onSelect: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        TextButton(onClick = onSelect, modifier = Modifier.weight(1f)) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = view.name,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = if (isEditing) FontWeight.SemiBold else FontWeight.Normal,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (isEditing) {
                    Text(
                        text = stringResource(R.string.views_editing),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
        GlyphButton("↑", stringResource(R.string.views_move_up, view.name), enabled = canMoveUp, onClick = onMoveUp)
        GlyphButton("↓", stringResource(R.string.views_move_down, view.name), enabled = canMoveDown, onClick = onMoveDown)
        GlyphButton("✎", stringResource(R.string.views_rename, view.name), enabled = true, onClick = onRename)
        GlyphButton("✕", stringResource(R.string.views_delete, view.name), enabled = canDelete, onClick = onDelete)
    }
}

/** Kiosk setting: return to the first view after N minutes without touches. Saved immediately, unlike the views. */
@Composable
private fun InactivityReturnSection(selectedMinutes: Int, onChange: (Int) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.views_inactivity_title), style = MaterialTheme.typography.titleSmall)
        Text(
            text = stringResource(R.string.views_inactivity_help),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            INACTIVITY_RETURN_OPTIONS.forEach { minutes ->
                FilterChip(
                    selected = minutes == selectedMinutes,
                    onClick = { if (minutes != selectedMinutes) onChange(minutes) },
                    label = {
                        Text(
                            if (minutes == 0) {
                                stringResource(R.string.views_inactivity_off)
                            } else {
                                stringResource(R.string.views_inactivity_minutes, minutes)
                            },
                        )
                    },
                )
            }
        }
    }
}

@Composable
private fun RenameRow(initialName: String, onSave: (String) -> Unit, onCancel: () -> Unit) {
    var name by rememberSaveable { mutableStateOf(initialName) }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text(stringResource(R.string.views_name_label)) },
            singleLine = true,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onCancel) { Text(stringResource(R.string.views_rename_cancel)) }
        Button(onClick = { onSave(name.trim()) }, enabled = name.isNotBlank()) {
            Text(stringResource(R.string.views_rename_save))
        }
    }
}

/** Icon-sized button with a text glyph (the project has no icon library) and an accessible label. */
@Composable
private fun GlyphButton(glyph: String, description: String, enabled: Boolean, onClick: () -> Unit) {
    IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.semantics { contentDescription = description }) {
        Text(glyph, style = MaterialTheme.typography.titleMedium)
    }
}

@Preview(showBackground = true, widthDp = 700, heightDp = 800)
@Composable
private fun ViewsModalPreview() {
    HAKioskTheme {
        ViewsModal(
            views = listOf(
                ViewListItem("main", "Principal"),
                ViewListItem("upstairs", "Planta alta"),
                ViewListItem("garden", "Jardín y pileta"),
            ),
            editingViewId = "upstairs",
            onAddView = {},
            onRenameView = { _, _ -> },
            onRemoveView = {},
            onMoveView = { _, _ -> },
            onSelectView = {},
            inactivityReturnMinutes = 5,
            onInactivityReturnChange = {},
            onDismiss = {},
        )
    }
}
