package com.matiasnl.hakiosk.ui.dashboard.tiles.light

import com.matiasnl.hakiosk.data.ha.HaEntity
import com.matiasnl.hakiosk.data.ha.domain.LightCapabilities
import com.matiasnl.hakiosk.ui.dashboard.tiles.DomainTileBehavior
import com.matiasnl.hakiosk.ui.dashboard.tiles.TileAction
import com.matiasnl.hakiosk.ui.dashboard.tiles.TileDetails
import com.matiasnl.hakiosk.ui.dashboard.tiles.TileSummary

/** Lights: a tap toggles (or opens the panel, per tile); a long press opens brightness/temperature/color controls. */
object LightTileBehavior : DomainTileBehavior {
    override val tapAction: TileAction = TileAction.TOGGLE

    override val details: TileDetails get() = LightTileDetails

    override fun summarize(entity: HaEntity): TileSummary {
        val capabilities = LightCapabilities.from(entity)
        // A 1/255 brightness rounds to 0%, which would read as "off": show at least 1% while on.
        val percent = capabilities.brightnessPercent
            ?.takeIf { capabilities.isOn && capabilities.supportsBrightness }
            ?.coerceAtLeast(1)
        return TileSummary.Light(isOn = capabilities.isOn, brightnessPercent = percent)
    }
}
