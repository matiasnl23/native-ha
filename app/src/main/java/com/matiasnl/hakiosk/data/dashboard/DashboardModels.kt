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
    /** A Home Assistant entity button. [label] overrides the entity's friendly name when not null. */
    @Serializable
    @SerialName("entity")
    data class Entity(val entityId: String, val label: String? = null) : TileContent

    /** Navigates to another [DashboardView] when tapped. */
    @Serializable
    @SerialName("view_link")
    data class ViewLink(val targetViewId: String, val label: String? = null) : TileContent

    /** Occupies cells but shows nothing outside edit mode; visually separates groups of tiles. */
    @Serializable
    @SerialName("spacer")
    data object Spacer : TileContent
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
 * A brand-new single-view layout: one empty view named [PRINCIPAL_VIEW_NAME]. Used when nothing is
 * stored yet, and as the fallback when persisted data can't be read.
 */
fun defaultDashboardLayout(idProvider: DashboardIdProvider = UuidDashboardIdProvider): DashboardLayout =
    DashboardLayout(views = listOf(DashboardView(id = idProvider.newId(), name = PRINCIPAL_VIEW_NAME)))
