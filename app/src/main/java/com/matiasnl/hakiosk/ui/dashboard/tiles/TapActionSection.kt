package com.matiasnl.hakiosk.ui.dashboard.tiles

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.matiasnl.hakiosk.R
import com.matiasnl.hakiosk.data.dashboard.TileTapAction
import com.matiasnl.hakiosk.ui.theme.HAKioskTheme

/**
 * "Al tocar": whether a tap toggles the entity or opens its controls. "Alternar" is stored as
 * [TileTapAction.DEFAULT] (the domain's own default, which toggles) so the saved JSON stays compact.
 */
@Composable
fun TapActionSection(tapAction: TileTapAction, onTapActionChange: (TileTapAction) -> Unit) {
    val opensDetails = tapAction == TileTapAction.OPEN_DETAILS
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.edit_tile_tap_action), style = MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = !opensDetails,
                onClick = { onTapActionChange(TileTapAction.DEFAULT) },
                label = { Text(stringResource(R.string.edit_tile_tap_action_toggle)) },
            )
            FilterChip(
                selected = opensDetails,
                onClick = { onTapActionChange(TileTapAction.OPEN_DETAILS) },
                label = { Text(stringResource(R.string.edit_tile_tap_action_open_details)) },
            )
        }
        Text(
            text = stringResource(
                if (opensDetails) R.string.edit_tile_tap_action_open_details_hint else R.string.edit_tile_tap_action_toggle_hint,
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Preview(showBackground = true, widthDp = 420)
@Composable
private fun TapActionSectionPreview() {
    HAKioskTheme {
        TapActionSection(tapAction = TileTapAction.OPEN_DETAILS, onTapActionChange = {})
    }
}
