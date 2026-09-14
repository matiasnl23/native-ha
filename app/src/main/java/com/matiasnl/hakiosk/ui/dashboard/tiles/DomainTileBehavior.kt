package com.matiasnl.hakiosk.ui.dashboard.tiles

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
}

/**
 * How one Home Assistant domain behaves on the dashboard: what a tap does. Pure Kotlin so the
 * mapping is unit-testable; every domain without an entry uses [DefaultTileBehavior].
 *
 * Adding a domain is one entry in [DomainTileBehaviors]: e.g. `"cover" to ToggleTileBehavior`.
 */
interface DomainTileBehavior {
    /** The action a tap runs. */
    val tapAction: TileAction
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
        "light" to ToggleTileBehavior,
        "switch" to ToggleTileBehavior,
        "fan" to ToggleTileBehavior,
        "input_boolean" to ToggleTileBehavior,
        "automation" to ToggleTileBehavior,
        "scene" to TurnOnTileBehavior,
        "script" to TurnOnTileBehavior,
        CAMERA_DOMAIN to CameraTileBehavior,
    )

    fun forDomain(domain: String): DomainTileBehavior = byDomain[domain] ?: DefaultTileBehavior
}
