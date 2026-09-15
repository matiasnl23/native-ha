package com.matiasnl.hakiosk.ui.dashboard.tiles.climate

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
import com.matiasnl.hakiosk.data.dashboard.TileStyle
import com.matiasnl.hakiosk.ui.theme.HAKioskTheme

/** "Estilo" for a climate tile: "Lectura" (stored as [TileStyle.DEFAULT]) or "Ajuste rápido". */
@Composable
fun ClimateStyleSection(style: TileStyle, onStyleChange: (TileStyle) -> Unit) {
    val quickAdjust = style == TileStyle.QUICK_ADJUST
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.edit_tile_style), style = MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = !quickAdjust,
                onClick = { onStyleChange(TileStyle.DEFAULT) },
                label = { Text(stringResource(R.string.climate_style_reading)) },
            )
            FilterChip(
                selected = quickAdjust,
                onClick = { onStyleChange(TileStyle.QUICK_ADJUST) },
                label = { Text(stringResource(R.string.climate_style_quick_adjust)) },
            )
        }
        Text(
            text = stringResource(if (quickAdjust) R.string.climate_style_quick_adjust_hint else R.string.climate_style_reading_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Preview(showBackground = true, widthDp = 420)
@Composable
private fun ClimateStyleSectionPreview() {
    HAKioskTheme {
        ClimateStyleSection(style = TileStyle.QUICK_ADJUST, onStyleChange = {})
    }
}
