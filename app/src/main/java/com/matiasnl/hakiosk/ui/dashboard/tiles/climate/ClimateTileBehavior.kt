package com.matiasnl.hakiosk.ui.dashboard.tiles.climate

import com.matiasnl.hakiosk.data.ha.HaEntity
import com.matiasnl.hakiosk.data.ha.domain.ClimateCapabilities
import com.matiasnl.hakiosk.data.ha.domain.HvacMode
import com.matiasnl.hakiosk.ui.dashboard.tiles.DomainTileBehavior
import com.matiasnl.hakiosk.ui.dashboard.tiles.TileAction
import com.matiasnl.hakiosk.ui.dashboard.tiles.TileDetails
import com.matiasnl.hakiosk.ui.dashboard.tiles.TileSummary

/**
 * Climate entities (air conditioners, thermostats). A tap opens the panel, like HA's own tile card:
 * there's no single obvious "toggle" for a device with several modes, and a stray tap shouldn't start one.
 */
object ClimateTileBehavior : DomainTileBehavior {
    override val tapAction: TileAction = TileAction.OPEN_DETAILS

    override val details: TileDetails get() = ClimateTileDetails

    override fun summarize(entity: HaEntity): TileSummary {
        val capabilities = ClimateCapabilities.from(entity)
        val range = capabilities.usesTargetRange
        return TileSummary.Climate(
            hvacMode = capabilities.hvacMode,
            hvacAction = capabilities.hvacAction,
            currentTemperature = capabilities.currentTemperature,
            targetTemperature = capabilities.targetTemperature.takeIf { !range && capabilities.hasTargetTemperature },
            targetTemperatureLow = capabilities.targetTemperatureLow.takeIf { range },
            targetTemperatureHigh = capabilities.targetTemperatureHigh.takeIf { range },
        )
    }
}

/**
 * Whether the low/high range is the setpoint to show. Like HA's frontend: the range in `heat_cool`, or
 * whenever the device reports a range but no single setpoint.
 */
internal val ClimateCapabilities.usesTargetRange: Boolean
    get() = hasTargetTemperatureRange && (hvacMode == HvacMode.HEAT_COOL || !hasTargetTemperature)
