package com.matiasnl.hakiosk.ui.dashboard.tiles.alarm

import com.matiasnl.hakiosk.data.ha.HaEntity
import com.matiasnl.hakiosk.data.ha.domain.AlarmPanelState
import com.matiasnl.hakiosk.ui.dashboard.tiles.DomainTileBehavior
import com.matiasnl.hakiosk.ui.dashboard.tiles.TileAction
import com.matiasnl.hakiosk.ui.dashboard.tiles.TileDetails
import com.matiasnl.hakiosk.ui.dashboard.tiles.TileSummary

/**
 * Alarm control panels. For safety a tap only ever opens the panel (never arms or disarms), whatever
 * the tile's stored tap preference; the edit modal offers no choice.
 */
object AlarmTileBehavior : DomainTileBehavior {
    override val tapAction: TileAction = TileAction.OPEN_DETAILS

    override val details: TileDetails get() = AlarmTileDetails

    override fun summarize(entity: HaEntity): TileSummary = TileSummary.Alarm(AlarmPanelState.from(entity))
}
