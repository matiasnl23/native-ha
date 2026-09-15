package com.matiasnl.hakiosk.ui.dashboard.tiles

import androidx.compose.runtime.Composable
import com.matiasnl.hakiosk.data.dashboard.TileStyle
import com.matiasnl.hakiosk.data.dashboard.TileTapAction
import com.matiasnl.hakiosk.data.ha.HaEntity
import com.matiasnl.hakiosk.ui.dashboard.tiles.alarm.AlarmTileBehavior
import com.matiasnl.hakiosk.ui.dashboard.tiles.climate.ClimateTileBehavior
import com.matiasnl.hakiosk.ui.dashboard.tiles.light.LightTileBehavior

/** What a tap on an entity tile does, once the domain and the tile's own preference are resolved. */
enum class TileAction {
    /** Display-only tile: the tap does nothing. */
    NONE,

    /** Calls `<domain>.toggle`. */
    TOGGLE,

    /** Calls `<domain>.turn_on` (running a scene/script is not really "on/off"). */
    TURN_ON,

    /** Opens the full-screen camera view. */
    OPEN_CAMERA,

    /** Opens the domain's details panel ([DomainTileBehavior.details]). */
    OPEN_DETAILS,
}

/**
 * How one Home Assistant domain behaves on the dashboard. Every domain without an entry in
 * [DomainTileBehaviors] uses [DefaultTileBehavior].
 *
 * - [tapAction]: what a tap does by default.
 * - [details]: the panel a long press (outside edit mode) opens, or null if the domain has none.
 * - [offersTapActionChoice]: whether the edit modal lets the user pick between toggling and opening
 *   the panel on tap (derived: the domain toggles and has a panel).
 *
 * Adding a domain is one entry in [DomainTileBehaviors], e.g. `"cover" to ToggleTileBehavior`, or an
 * object overriding [details] for a domain with its own panel.
 */
interface DomainTileBehavior {
    /** The domain's default tap action. */
    val tapAction: TileAction

    /** The details panel, or null if the domain has none. */
    val details: TileDetails? get() = null

    val offersTapActionChoice: Boolean get() = details != null && tapAction == TileAction.TOGGLE

    /** Whether the edit modal offers a [TileStyle] choice; the domain's [editSections] then shows it. */
    val offersStyleChoice: Boolean get() = false

    /** The tap action for a tile with [preference]; the preference only matters when [offersTapActionChoice]. */
    fun resolveTap(preference: TileTapAction): TileAction {
        if (!offersTapActionChoice) return tapAction
        return when (preference) {
            TileTapAction.DEFAULT, TileTapAction.TOGGLE -> TileAction.TOGGLE
            TileTapAction.OPEN_DETAILS -> TileAction.OPEN_DETAILS
        }
    }

    /**
     * The tile's summary for [entity]. Called at most once per entity state change (the ViewModel
     * caches it by entity instance), so parsing attributes here is fine.
     */
    fun summarize(entity: HaEntity): TileSummary = TileSummary.Default

    /**
     * The edit modal's sections for a tile of this domain (passed as `EditTileModal`'s `domainSections`).
     * They edit modal-local state, so a change only reaches the working copy when the modal is applied.
     */
    fun editSections(
        tapAction: TileTapAction,
        onTapActionChange: (TileTapAction) -> Unit,
        style: TileStyle,
        onStyleChange: (TileStyle) -> Unit,
    ): List<@Composable () -> Unit> =
        if (offersTapActionChoice) listOf { TapActionSection(tapAction, onTapActionChange) } else emptyList()
}

/** Read-only tiles (sensors, binary sensors, anything unknown): taps are no-ops. */
object DefaultTileBehavior : DomainTileBehavior {
    override val tapAction: TileAction = TileAction.NONE
}

/** On/off entities whose tap toggles them. */
object ToggleTileBehavior : DomainTileBehavior {
    override val tapAction: TileAction = TileAction.TOGGLE
}

/** Scenes and scripts: a tap runs them with `turn_on`. */
object TurnOnTileBehavior : DomainTileBehavior {
    override val tapAction: TileAction = TileAction.TURN_ON
}

/** Cameras: a tap opens the focus view. */
object CameraTileBehavior : DomainTileBehavior {
    override val tapAction: TileAction = TileAction.OPEN_CAMERA
}

/** Registry of per-domain tile behaviors. */
object DomainTileBehaviors {
    const val CAMERA_DOMAIN = "camera"

    private val byDomain: Map<String, DomainTileBehavior> = mapOf(
        "light" to LightTileBehavior,
        "switch" to ToggleTileBehavior,
        "fan" to ToggleTileBehavior,
        "input_boolean" to ToggleTileBehavior,
        "automation" to ToggleTileBehavior,
        "scene" to TurnOnTileBehavior,
        "script" to TurnOnTileBehavior,
        CAMERA_DOMAIN to CameraTileBehavior,
        "alarm_control_panel" to AlarmTileBehavior,
        "climate" to ClimateTileBehavior,
    )

    fun forDomain(domain: String): DomainTileBehavior = byDomain[domain] ?: DefaultTileBehavior
}
