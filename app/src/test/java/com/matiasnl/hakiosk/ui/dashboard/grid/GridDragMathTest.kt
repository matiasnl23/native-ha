package com.matiasnl.hakiosk.ui.dashboard.grid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Geometry used throughout: gutter 10px and cells of exactly 100px, so column c spans
 * x ∈ [10 + 110c, 110 + 110c) and its center is 60 + 110c (same for rows and y).
 */
class GridDragMathTest {

    private fun metrics(columns: Int, rows: Int = 4) =
        GridMetrics(columns, rows, viewportWidth = 10 + columns * 110, viewportHeight = 10 + rows * 110, gutter = 10f)

    private fun center(cell: Int): Float = 60f + 110f * cell

    /** A tile in a simulated drag: [id] plus spans. */
    private data class T(val id: String, val colSpan: Int = 1, val rowSpan: Int = 1)

    private val packer = GridPacker()

    private fun pack(columns: Int, tiles: List<T>, withAddTile: Boolean = false): GridPacking {
        val all = if (withAddTile) tiles + T("+") else tiles
        return packer.pack(columns, all, { it.colSpan }, { it.rowSpan })
    }

    private fun <E> MutableList<E>.move(from: Int, to: Int) = add(to, removeAt(from))

    // --- dropTarget ---

    @Test
    fun `pointer in the inner area of another tile targets that tile`() {
        // 4 cols: a b c d on row 0.
        val tiles = listOf(T("a"), T("b"), T("c"), T("d"))
        val packing = pack(4, tiles)

        val target = GridDragMath.dropTarget(metrics(4), packing.placements, 4, draggedIndex = 0, x = center(2), contentY = center(0))

        assertEquals(2, target)
    }

    @Test
    fun `outer ring of another tile, the dragged tile itself and gutters keep the current index`() {
        val tiles = listOf(T("a"), T("b"), T("c"), T("d"))
        val packing = pack(4, tiles)
        val m = metrics(4)

        // Column 2 spans x 230..330; its inner 60% is 250..310.
        assertEquals(0, GridDragMath.dropTarget(m, packing.placements, 4, 0, x = 235f, contentY = center(0)))
        assertEquals(0, GridDragMath.dropTarget(m, packing.placements, 4, 0, x = center(0), contentY = center(0)))
        assertEquals(0, GridDragMath.dropTarget(m, packing.placements, 4, 0, x = 225f, contentY = center(0))) // Gutter.
    }

    @Test
    fun `empty cell uses reading-order insertion`() {
        // 3 cols: A 2×2 at (0,0), b (0,2), c (1,2), d (2,0); cells (2,1) and (2,2) are empty.
        val tiles = listOf(T("A", 2, 2), T("b"), T("c"), T("d"))
        val packing = pack(3, tiles)
        val m = metrics(3)

        // Dragging b over (2,1): tiles other than b starting before (2,1) are A, c, d -> 3.
        assertEquals(3, GridDragMath.dropTarget(m, packing.placements, 4, draggedIndex = 1, x = center(1), contentY = center(2)))
        // Dragging d over (2,1): only d itself starts on row 2 before it, so A, b, c -> 3 = its own index.
        assertEquals(3, GridDragMath.dropTarget(m, packing.placements, 4, draggedIndex = 3, x = center(1), contentY = center(2)))
        // Dragging A over the empty (2,2): b, c, d start before -> 3.
        assertEquals(3, GridDragMath.dropTarget(m, packing.placements, 4, draggedIndex = 0, x = center(2), contentY = center(2)))
    }

    @Test
    fun `empty cells far past the end never target beyond the last real tile`() {
        val tiles = listOf(T("a"), T("b"), T("c"))
        val packing = pack(3, tiles)

        val target = GridDragMath.dropTarget(metrics(3, rows = 6), packing.placements, 3, draggedIndex = 0, x = center(2), contentY = center(5))

        assertEquals(2, target)
    }

    @Test
    fun `the add tile is not a target and its cell counts as empty`() {
        // 3 cols: a b c on row 0, "+" at (1,0).
        val tiles = listOf(T("a"), T("b"), T("c"))
        val packing = pack(3, tiles, withAddTile = true)
        val m = metrics(3)

        assertEquals(-1, GridDragMath.tileAt(m, packing.placements, 3, center(0), center(1)))
        // Over "+": a, c start before (1,0) when dragging b -> index 2, the last real index.
        assertEquals(2, GridDragMath.dropTarget(m, packing.placements, 3, draggedIndex = 1, x = center(0), contentY = center(1)))
        // The "+" itself can never be dragged.
        assertEquals(3, GridDragMath.dropTarget(m, packing.placements, 3, draggedIndex = 3, x = center(0), contentY = center(0)))
    }

    @Test
    fun `tracker applies the scroll offset to the pointer`() {
        // 3 cols × 2 visible rows, 3 rows of tiles; row 2 is only visible after scrolling.
        val tiles = List(9) { T("t$it") }
        val packing = pack(3, tiles)
        val m = metrics(3, rows = 2)

        // Viewport y at row 0's center, scrolled by 2 rows (220px): the pointer is over t7 at (2,1).
        val scrolled = GridDragTracker().update(m, packing.placements, 9, draggedIndex = 0, x = center(1), viewportY = center(0), scrollOffset = 220)
        val unscrolled = GridDragTracker().update(m, packing.placements, 9, draggedIndex = 0, x = center(1), viewportY = center(0), scrollOffset = 0)

        assertEquals(7, scrolled)
        assertEquals(1, unscrolled)
    }

    // --- Hysteresis ---

    /**
     * Drives a [GridDragTracker] the way the grid does: evaluate at a fixed pointer, apply every
     * target change to the list, re-pack, repeat. Returns the order after [steps] and the number of changes.
     */
    private fun settle(
        columns: Int,
        start: List<T>,
        draggedId: String,
        x: Float,
        y: Float,
        tracker: GridDragTracker = GridDragTracker(),
        steps: Int = 10,
    ): Pair<List<String>, Int> {
        val tiles = start.toMutableList()
        var changes = 0
        repeat(steps) {
            val packing = pack(columns, tiles, withAddTile = true)
            val dragged = tiles.indexOfFirst { it.id == draggedId }
            val target = tracker.update(metrics(columns), packing.placements, tiles.size, dragged, x, y, scrollOffset = 0)
            if (target != dragged) {
                tiles.move(dragged, target)
                changes++
            }
        }
        return tiles.map { it.id } to changes
    }

    @Test
    fun `a 2x2 dragged over 1x1 tiles moves once and stays put`() {
        // 4 cols: D 2×2 at (0,0)-(1,1), a (0,2), b (0,3), c (1,2), d (1,3), e (2,0), f (2,1).
        val start = listOf(T("D", 2, 2), T("a"), T("b"), T("c"), T("d"), T("e"), T("f"))

        for (cell in listOf(2 to 0, 3 to 0, 2 to 1, 3 to 1, 0 to 2, 1 to 2)) {
            val (order, changes) = settle(4, start, "D", x = center(cell.first), y = center(cell.second))
            assertTrue("pointer at $cell changed $changes times: $order", changes <= 1)
        }
    }

    @Test
    fun `a 1x1 dragged over a 2x2 tile moves once and stays put`() {
        // 4 cols: x (0,0), B 2×2 at (0,1)-(1,2), c (0,3), d (1,0), e (1,3).
        val start = listOf(T("x"), T("B", 2, 2), T("c"), T("d"), T("e"))

        // x = 280 is inside B's inner area (B spans 120..330, inner 162..288).
        val (order, changes) = settle(4, start, "x", x = 280f, y = center(0))

        assertEquals(1, changes)
        assertEquals(listOf("B", "x", "c", "d", "e"), order)
    }

    @Test
    fun `without hysteresis a 1x1 over a wide tile oscillates, the tracker settles after one move`() {
        // 6 cols: x (0,0), W 4×1 at cols 1-4 (x 120..560, inner 208..472). Pointer at x = 240.
        val m = metrics(6)
        val tiles = mutableListOf(T("x"), T("W", 4, 1), T("y"))
        val px = 240f
        val py = center(0)

        // Stateless rule: after x moves behind W, W re-packs to cols 0-3 (inner 98..362) and the
        // pointer is still in its inner area, so the next evaluation moves x straight back.
        val first = GridDragMath.dropTarget(m, pack(6, tiles).placements, 3, draggedIndex = 0, x = px, contentY = py)
        assertEquals(1, first)
        tiles.move(0, 1)
        val second = GridDragMath.dropTarget(m, pack(6, tiles).placements, 3, draggedIndex = 1, x = px, contentY = py)
        assertEquals(0, second)

        val (order, changes) = settle(6, listOf(T("x"), T("W", 4, 1), T("y")), "x", x = px, y = py)
        assertEquals(1, changes)
        assertEquals(listOf("W", "x", "y"), order)
    }

    @Test
    fun `sliding a 1x1 across a wide tile swaps once instead of at every cell boundary`() {
        val tracker = GridDragTracker()
        val tiles = mutableListOf(T("x"), T("W", 4, 1), T("y"))
        val m = metrics(6)
        var changes = 0
        // Slide right across W and back into its outer ring, 5px per event, re-packing after each
        // change. (Coming back further, into the re-packed W's inner area, is a deliberate second swap.)
        val path = (130..480 step 5) + (480 downTo 380 step 5)
        for (px in path) {
            val dragged = tiles.indexOfFirst { it.id == "x" }
            val target = tracker.update(m, pack(6, tiles, withAddTile = true).placements, 3, dragged, px.toFloat(), center(0), 0)
            if (target != dragged) {
                tiles.move(dragged, target)
                changes++
            }
        }

        assertEquals(1, changes)
        assertEquals(listOf("W", "x", "y"), tiles.map { it.id })
    }

    @Test
    fun `a 2x2 dragged across a row of 1x1 tiles never flips back while the finger is still`() {
        val start = listOf(T("a"), T("b"), T("D", 2, 2), T("c"), T("d"), T("e"), T("f"))
        for (col in 0..3) {
            for (row in 0..2) {
                val (_, changes) = settle(4, start, "D", x = center(col), y = center(row))
                assertTrue("pointer at ($col,$row) changed $changes times", changes <= 1)
            }
        }
    }

    @Test
    fun `leaving the locked cell re-enables retargeting`() {
        val tracker = GridDragTracker()
        val tiles = mutableListOf(T("a"), T("b"), T("c"), T("d"))
        val m = metrics(4)

        fun step(px: Float): Int {
            val dragged = tiles.indexOfFirst { it.id == "a" }
            val target = tracker.update(m, pack(4, tiles).placements, 4, dragged, px, center(0), 0)
            if (target != dragged) tiles.move(dragged, target)
            return target
        }

        assertEquals(1, step(center(1))) // a <-> b
        assertEquals(1, step(center(1) + 20f)) // Same cell: locked.
        assertEquals(2, step(center(2))) // Next cell, c's inner area.
        assertEquals(listOf("b", "c", "a", "d"), tiles.map { it.id })
    }

    // --- Helpers ---

    @Test
    fun `index after move follows list remove and insert`() {
        val list = mutableListOf("a", "b", "c", "d", "e")
        val moved = list.toMutableList().apply { move(1, 3) }
        for (i in list.indices) {
            assertEquals(list[i], moved[GridDragMath.indexAfterMove(i, 1, 3)])
        }
        val movedBack = list.toMutableList().apply { move(4, 0) }
        for (i in list.indices) {
            assertEquals(list[i], movedBack[GridDragMath.indexAfterMove(i, 4, 0)])
        }
    }

    @Test
    fun `autoscroll velocity is proportional to depth into the edge bands and capped`() {
        val height = 1000
        val band = 50f
        val max = 800f

        assertEquals(0f, GridDragMath.autoscrollVelocity(500f, height, band, max), 0f)
        assertEquals(0f, GridDragMath.autoscrollVelocity(50f, height, band, max), 0f)
        assertEquals(-400f, GridDragMath.autoscrollVelocity(25f, height, band, max), 0.01f)
        assertEquals(-800f, GridDragMath.autoscrollVelocity(-30f, height, band, max), 0.01f)
        assertEquals(400f, GridDragMath.autoscrollVelocity(975f, height, band, max), 0.01f)
        assertEquals(800f, GridDragMath.autoscrollVelocity(1200f, height, band, max), 0.01f)
    }
}
