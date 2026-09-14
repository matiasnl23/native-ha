package com.matiasnl.hakiosk.data.dashboard

/**
 * Pure operations on [DashboardLayout]. Every one of them returns a new layout and never mutates its
 * receiver. Unknown view/tile ids and out-of-range indices are a no-op (the unchanged layout is
 * returned), never a crash: callers may hold onto an id or index that went stale between reading the
 * UI state and applying the edit (e.g. a concurrent change from another collector).
 */

/** Inserts [tile] into [viewId]'s tile list at [index] (end of the list when null, clamped otherwise). */
fun DashboardLayout.addTile(viewId: String, tile: DashboardTile, index: Int? = null): DashboardLayout =
    updateView(viewId) { view ->
        val at = (index ?: view.tiles.size).coerceIn(0, view.tiles.size)
        view.copy(tiles = view.tiles.toMutableList().apply { add(at, tile.clamped()) })
    }

/** Applies [transform] to the tile [tileId] in [viewId], re-clamping its spans afterwards. */
fun DashboardLayout.updateTile(
    viewId: String,
    tileId: String,
    transform: (DashboardTile) -> DashboardTile,
): DashboardLayout = updateView(viewId) { view ->
    view.copy(tiles = view.tiles.map { if (it.id == tileId) transform(it).clamped() else it })
}

fun DashboardLayout.removeTile(viewId: String, tileId: String): DashboardLayout =
    updateView(viewId) { view -> view.copy(tiles = view.tiles.filterNot { it.id == tileId }) }

/** Moves the tile at [fromIndex] to [toIndex] (clamped into range) within [viewId]'s tile list. */
fun DashboardLayout.moveTile(viewId: String, fromIndex: Int, toIndex: Int): DashboardLayout =
    updateView(viewId) { view ->
        val tiles = view.tiles
        if (fromIndex !in tiles.indices) return@updateView view
        val target = toIndex.coerceIn(0, tiles.lastIndex)
        view.copy(tiles = tiles.toMutableList().apply { add(target, removeAt(fromIndex)) })
    }

/** Appends a new empty view named [name] at the end. Returns the updated layout and the new view's id. */
fun DashboardLayout.addView(
    name: String,
    idProvider: DashboardIdProvider = UuidDashboardIdProvider,
): Pair<DashboardLayout, String> {
    val id = idProvider.newId()
    return copy(views = views + DashboardView(id = id, name = name)) to id
}

fun DashboardLayout.renameView(viewId: String, name: String): DashboardLayout =
    updateView(viewId) { it.copy(name = name) }

/**
 * Removes [viewId], unless it is the last remaining view (invariant: at least one view always
 * exists). Also drops any [TileContent.ViewLink] tile in the remaining views that pointed at it, so
 * the dashboard never keeps a dangling link.
 */
fun DashboardLayout.removeView(viewId: String): DashboardLayout {
    if (views.size <= 1 || views.none { it.id == viewId }) return this
    val remaining = views.filterNot { it.id == viewId }
    val cleaned = remaining.map { view ->
        view.copy(
            tiles = view.tiles.filterNot { tile ->
                val content = tile.content
                content is TileContent.ViewLink && content.targetViewId == viewId
            },
        )
    }
    return copy(views = cleaned)
}

/** Moves the view at [fromIndex] to [toIndex] (clamped into range). */
fun DashboardLayout.moveView(fromIndex: Int, toIndex: Int): DashboardLayout {
    if (fromIndex !in views.indices) return this
    val target = toIndex.coerceIn(0, views.lastIndex)
    return copy(views = views.toMutableList().apply { add(target, removeAt(fromIndex)) })
}

fun DashboardLayout.setGrid(viewId: String, grid: DashboardGrid): DashboardLayout =
    updateView(viewId) { it.copy(grid = grid.clamped()) }

private fun DashboardLayout.updateView(viewId: String, transform: (DashboardView) -> DashboardView): DashboardLayout {
    if (views.none { it.id == viewId }) return this
    return copy(views = views.map { if (it.id == viewId) transform(it) else it })
}

private fun DashboardTile.clamped(): DashboardTile =
    copy(colSpan = colSpan.coerceToCellRange(), rowSpan = rowSpan.coerceToCellRange())

private fun DashboardGrid.clamped(): DashboardGrid =
    copy(columns = columns.coerceToCellRange(), rows = rows.coerceToCellRange())
