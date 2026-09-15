package com.matiasnl.hakiosk.data.dashboard

import java.util.UUID
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Generates ids for new views and tiles. Injectable so tests can produce deterministic ids. */
fun interface DashboardIdProvider {
    fun newId(): String
}

/** Default [DashboardIdProvider]: random UUIDs, stable identities for the tile/view's lifetime. */
object UuidDashboardIdProvider : DashboardIdProvider {
    override fun newId(): String = UUID.randomUUID().toString()
}

/**
 * Whole dashboard configuration: an ordered list of views.
 *
 * Invariant: at least one view always exists. [removeView] refuses to drop the last one; nothing
 * else in this module removes views, so every [DashboardLayout] in memory keeps the invariant as
 * long as it started from [defaultDashboardLayout] or a valid migration.
 */
@Serializable
data class DashboardLayout(val views: List<DashboardView>)

/**
 * One screen of the dashboard: an ordered list of tiles and its own grid size. Tile positions are
 * not stored here — a dense-packing algorithm (added in a later stage) turns the order and each
 * tile's [DashboardTile.colSpan]/[DashboardTile.rowSpan] into actual cell coordinates at render time.
 */
@Serializable
data class DashboardView(
    val id: String,
    val name: String,
    val grid: DashboardGrid = DashboardGrid(),
    val tiles: List<DashboardTile> = emptyList(),
)

/** Columns/rows visible without scrolling; more rows than fit scroll vertically. Clamped to [CELL_RANGE]. */
@Serializable
data class DashboardGrid(val columns: Int = 4, val rows: Int = 3)

/** One button of the dashboard grid, sized in cells. Clamped to [CELL_RANGE], independent of [DashboardGrid]:
 * columns can change later, and the packing algorithm clips oversized spans at render time. */
@Serializable
data class DashboardTile(
    val id: String,
    val content: TileContent,
    val colSpan: Int = 1,
    val rowSpan: Int = 1,
)

@Serializable
sealed interface TileContent {
    /**
     * A Home Assistant entity button. [label] overrides the entity's friendly name when not null.
     * [tapAction] is the tile's own tap preference; stored JSON from before it existed decodes as
     * [TileTapAction.DEFAULT], and the default is never written. [style] works the same way with
     * [TileStyle.DEFAULT].
     */
    @Serializable
    @SerialName("entity")
    data class Entity(
        val entityId: String,
        val label: String? = null,
        val tapAction: TileTapAction = TileTapAction.DEFAULT,
        val style: TileStyle = TileStyle.DEFAULT,
    ) : TileContent

    /** Navigates to another [DashboardView] when tapped. */
    @Serializable
    @SerialName("view_link")
    data class ViewLink(val targetViewId: String, val label: String? = null) : TileContent

    /** Occupies cells but shows nothing outside edit mode; visually separates groups of tiles. */
    @Serializable
    @SerialName("spacer")
    data object Spacer : TileContent
}

/**
 * What tapping an entity tile does, as chosen per tile in the edit modal. Only meaningful for domains
 * that offer both a quick action and a details panel (e.g. lights); other domains ignore it.
 */
@Serializable
enum class TileTapAction {
    /** Whatever the domain does by default. */
    @SerialName("default")
    DEFAULT,

    /** Toggle the entity. */
    @SerialName("toggle")
    TOGGLE,

    /** Open the domain's details panel. */
    @SerialName("open_details")
    OPEN_DETAILS,
}

/**
 * How an entity tile presents itself, as chosen per tile in the edit modal. Only domains that offer a
 * style choice (e.g. climate) read it; other domains ignore it.
 */
@Serializable
enum class TileStyle {
    /** The domain's own presentation. */
    @SerialName("default")
    DEFAULT,

    /** Adjustment buttons on the tile itself (e.g. a climate setpoint's − and +). */
    @SerialName("quick_adjust")
    QUICK_ADJUST,
}

/** Valid range for grid columns/rows and tile spans. */
val CELL_RANGE = 1..12

internal fun Int.coerceToCellRange(): Int = coerceIn(CELL_RANGE.first, CELL_RANGE.last)

/** Name given to the single view created by the migration from the pre-multi-view format. */
const val PRINCIPAL_VIEW_NAME = "Principal"

/** A brand-new tile with a fresh id; spans are clamped to [CELL_RANGE]. */
fun newDashboardTile(
    content: TileContent,
    colSpan: Int = 1,
    rowSpan: Int = 1,
    idProvider: DashboardIdProvider = UuidDashboardIdProvider,
): DashboardTile = DashboardTile(
    id = idProvider.newId(),
    content = content,
    colSpan = colSpan.coerceToCellRange(),
    rowSpan = rowSpan.coerceToCellRange(),
)

/**
 * Id of the view in [defaultDashboardLayout]. Fixed on purpose: the default layout is rebuilt on every
 * read while nothing is persisted, and edits addressed by view id (e.g. "add a tile to the view the UI
 * is showing") must still find that view when the store rebuilds it inside an update.
 */
const val DEFAULT_VIEW_ID = "main"

/**
 * A brand-new single-view layout: one empty view named [PRINCIPAL_VIEW_NAME] with [DEFAULT_VIEW_ID].
 * Used when nothing is stored yet, and as the fallback when persisted data can't be read.
 */
fun defaultDashboardLayout(): DashboardLayout =
    DashboardLayout(views = listOf(DashboardView(id = DEFAULT_VIEW_ID, name = PRINCIPAL_VIEW_NAME)))
