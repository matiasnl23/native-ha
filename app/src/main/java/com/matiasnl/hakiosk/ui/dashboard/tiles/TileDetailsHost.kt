package com.matiasnl.hakiosk.ui.dashboard.tiles

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.matiasnl.hakiosk.R

/** A request to show the details panel of one entity tile. */
data class TileDetailsRequest(
    val tileId: String,
    val entityId: String,
    val label: String,
    val domain: String,
)

/** A domain's details panel. Rendered only while open, so anything it collects stops when it closes. */
interface TileDetails {
    @Composable
    fun Panel(request: TileDetailsRequest, source: EntityControlSource, onDismiss: () -> Unit)
}

/**
 * Called on every touch inside a panel. Dialogs are separate windows, so the dashboard's own
 * touch observer never sees those touches; without this the inactivity timer would fire mid-use.
 */
private val LocalPanelUserActivity = staticCompositionLocalOf<() -> Unit> { {} }

/** Shows [request]'s domain panel, or nothing if its domain has none. */
@Composable
fun TileDetailsHost(
    request: TileDetailsRequest,
    source: EntityControlSource,
    onUserActivity: () -> Unit,
    onDismiss: () -> Unit,
) {
    val details = DomainTileBehaviors.forDomain(request.domain).details ?: return
    CompositionLocalProvider(LocalPanelUserActivity provides onUserActivity) {
        details.Panel(request, source, onDismiss)
    }
}

/** The shared dialog shell of every details panel: same shape and width as the edit-mode modals. */
@Composable
fun TilePanelDialog(
    title: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val onUserActivity by rememberUpdatedState(LocalPanelUserActivity.current)
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        TilePanelSurface(
            title = title,
            onDismiss = onDismiss,
            modifier = modifier.pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        awaitPointerEvent(PointerEventPass.Initial)
                        onUserActivity()
                    }
                }
            },
            content = content,
        )
    }
}

/** The panel's card without the dialog window, so previews can render it directly. */
@Composable
fun TilePanelSurface(
    title: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Surface(
        shape = MaterialTheme.shapes.extraLarge,
        tonalElevation = 6.dp,
        modifier = modifier.widthIn(max = 480.dp).fillMaxWidth().padding(24.dp),
    ) {
        Column(
            modifier = Modifier.verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.headlineSmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.tile_panel_close)) }
            }
            content()
        }
    }
}
