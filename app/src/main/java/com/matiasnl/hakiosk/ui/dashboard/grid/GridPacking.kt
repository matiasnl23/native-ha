package com.matiasnl.hakiosk.ui.dashboard.grid

/** Where one tile ended up: top-left cell ([row], [column], 0-based) and its effective spans. */
data class GridPlacement(val row: Int, val column: Int, val colSpan: Int, val rowSpan: Int) {
    /** Exclusive bottom row. */
    val endRow: Int get() = row + rowSpan

    /** Exclusive right column. */
    val endColumn: Int get() = column + colSpan
}

/**
 * Result of packing an ordered tile list into [columns] columns. [placements] is index-aligned with
 * the input list. [totalRows] is the number of rows the content occupies (0 for no tiles).
 */
data class GridPacking(val columns: Int, val placements: List<GridPlacement>, val totalRows: Int) {
    companion object {
        val Empty = GridPacking(columns = 1, placements = emptyList(), totalRows = 0)
    }
}

/**
 * Dense first-fit packing: tiles are taken in order and each goes to the first free position that
 * fits it, scanning rows top to bottom and, within a row, columns left to right. Holes left by
 * earlier big tiles are filled by later smaller ones. Column spans are clipped to the column count;
 * row spans are unbounded (the grid scrolls vertically). Deterministic.
 *
 * Holds a reusable occupancy buffer so repeated packs (drag & drop re-packs on every hover) don't
 * allocate beyond the result list. Not thread-safe: use one instance per caller/thread.
 */
class GridPacker {
    private var occupied = BooleanArray(64)

    /** Packs [count] tiles whose spans are given by index. */
    inline fun pack(columns: Int, count: Int, colSpanOf: (Int) -> Int, rowSpanOf: (Int) -> Int): GridPacking {
        val cols = columns.coerceAtLeast(1)
        begin(cols)
        val placements = ArrayList<GridPlacement>(count)
        for (index in 0 until count) {
            placements += place(cols, colSpanOf(index), rowSpanOf(index))
        }
        return GridPacking(columns = cols, placements = placements, totalRows = usedRows)
    }

    /** Packs [tiles] reading each tile's spans with [colSpanOf]/[rowSpanOf]. */
    inline fun <T> pack(
        columns: Int,
        tiles: List<T>,
        colSpanOf: (T) -> Int,
        rowSpanOf: (T) -> Int,
    ): GridPacking = pack(columns, tiles.size, { colSpanOf(tiles[it]) }, { rowSpanOf(tiles[it]) })

    // --- Internal state, public only because the inline functions above need it. ---

    @PublishedApi
    internal var usedRows = 0

    private var packColumns = 1
    private var capacityRows = 0

    /** First row that may still have a free cell; everything above it is full. */
    private var firstOpenRow = 0

    @PublishedApi
    internal fun begin(columns: Int) {
        // Only the area touched by the previous pack can be dirty.
        java.util.Arrays.fill(occupied, 0, (usedRows * packColumns).coerceAtMost(occupied.size), false)
        packColumns = columns
        capacityRows = occupied.size / columns
        usedRows = 0
        firstOpenRow = 0
    }

    @PublishedApi
    internal fun place(columns: Int, colSpan: Int, rowSpan: Int): GridPlacement {
        val cs = colSpan.coerceIn(1, columns)
        val rs = rowSpan.coerceAtLeast(1)
        var row = firstOpenRow
        while (true) {
            // Rows at or beyond usedRows are empty, so a fit is always found there at column 0.
            for (column in 0..columns - cs) {
                if (fits(row, column, cs, rs)) {
                    mark(row, column, cs, rs)
                    advanceFirstOpenRow()
                    return GridPlacement(row = row, column = column, colSpan = cs, rowSpan = rs)
                }
            }
            row++
        }
    }

    private fun fits(row: Int, column: Int, colSpan: Int, rowSpan: Int): Boolean {
        val lastRow = minOf(row + rowSpan, usedRows)
        for (r in row until lastRow) {
            val base = r * packColumns
            for (c in column until column + colSpan) {
                if (occupied[base + c]) return false
            }
        }
        return true
    }

    private fun mark(row: Int, column: Int, colSpan: Int, rowSpan: Int) {
        val endRow = row + rowSpan
        ensureRows(endRow)
        for (r in row until endRow) {
            val base = r * packColumns
            for (c in column until column + colSpan) occupied[base + c] = true
        }
        if (endRow > usedRows) usedRows = endRow
    }

    private fun advanceFirstOpenRow() {
        while (firstOpenRow < usedRows && isRowFull(firstOpenRow)) firstOpenRow++
    }

    private fun isRowFull(row: Int): Boolean {
        val base = row * packColumns
        for (c in 0 until packColumns) if (!occupied[base + c]) return false
        return true
    }

    private fun ensureRows(rows: Int) {
        if (rows <= capacityRows) return
        var newCapacity = capacityRows.coerceAtLeast(1)
        while (newCapacity < rows) newCapacity *= 2
        occupied = occupied.copyOf(newCapacity * packColumns)
        capacityRows = newCapacity
    }
}
