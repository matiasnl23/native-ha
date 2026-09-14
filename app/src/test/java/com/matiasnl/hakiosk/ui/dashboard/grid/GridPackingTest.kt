package com.matiasnl.hakiosk.ui.dashboard.grid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GridPackingTest {

    private val packer = GridPacker()

    /** Spans as (colSpan, rowSpan) pairs. */
    private fun pack(columns: Int, vararg spans: Pair<Int, Int>): GridPacking =
        packer.pack(columns, spans.toList(), { it.first }, { it.second })

    private fun p(row: Int, column: Int, colSpan: Int = 1, rowSpan: Int = 1) =
        GridPlacement(row, column, colSpan, rowSpan)

    @Test
    fun `empty list has no placements and no rows`() {
        val result = pack(4)
        assertEquals(emptyList<GridPlacement>(), result.placements)
        assertEquals(0, result.totalRows)
        assertEquals(4, result.columns)
    }

    @Test
    fun `unit tiles fill rows left to right`() {
        val result = pack(3, 1 to 1, 1 to 1, 1 to 1, 1 to 1)
        assertEquals(listOf(p(0, 0), p(0, 1), p(0, 2), p(1, 0)), result.placements)
        assertEquals(2, result.totalRows)
    }

    @Test
    fun `wide tile that does not fit the rest of the row wraps to the next row`() {
        val result = pack(4, 1 to 1, 1 to 1, 1 to 1, 2 to 1)
        assertEquals(p(1, 0, colSpan = 2), result.placements[3])
        assertEquals(2, result.totalRows)
    }

    @Test
    fun `hole left by a wrapped wide tile is filled by a later small tile`() {
        // Row 0: A A A _   -> the 2-wide tile wraps, the 1x1 goes back to fill (0,3).
        val result = pack(4, 3 to 1, 2 to 1, 1 to 1)
        assertEquals(listOf(p(0, 0, colSpan = 3), p(1, 0, colSpan = 2), p(0, 3)), result.placements)
        assertEquals(2, result.totalRows)
    }

    @Test
    fun `tall tile blocks the cells below it`() {
        // Column 0 is taken by a 1x2 tile in rows 0-1; the next row-1 tile must start at column 1.
        val result = pack(3, 1 to 2, 1 to 1, 1 to 1, 1 to 1, 1 to 1)
        assertEquals(
            listOf(p(0, 0, rowSpan = 2), p(0, 1), p(0, 2), p(1, 1), p(1, 2)),
            result.placements,
        )
        assertEquals(2, result.totalRows)
    }

    @Test
    fun `tall tile height counts toward total rows`() {
        val result = pack(4, 1 to 5)
        assertEquals(p(0, 0, rowSpan = 5), result.placements.single())
        assertEquals(5, result.totalRows)
    }

    @Test
    fun `wide tile skips a row whose free cells are split by a tall tile`() {
        // Row 1 has free cells 0 and 2 but not two adjacent ones: the 2x1 goes to row 2.
        val result = pack(3, 1 to 1, 1 to 2, 1 to 1, 2 to 1, 1 to 1, 1 to 1)
        assertEquals(
            listOf(p(0, 0), p(0, 1, rowSpan = 2), p(0, 2), p(2, 0, colSpan = 2), p(1, 0), p(1, 2)),
            result.placements,
        )
        assertEquals(3, result.totalRows)
    }

    @Test
    fun `big square tile around smaller ones`() {
        val result = pack(4, 1 to 1, 2 to 2, 1 to 1, 1 to 1, 1 to 1)
        assertEquals(
            listOf(p(0, 0), p(0, 1, 2, 2), p(0, 3), p(1, 0), p(1, 3)),
            result.placements,
        )
        assertEquals(2, result.totalRows)
    }

    @Test
    fun `column span larger than columns is clipped`() {
        val result = pack(2, 5 to 1, 1 to 1)
        assertEquals(listOf(p(0, 0, colSpan = 2), p(1, 0)), result.placements)
    }

    @Test
    fun `shrinking columns clips spans and repacks`() {
        val spans = arrayOf(4 to 1, 3 to 2, 1 to 1)
        val wide = pack(6, *spans)
        assertEquals(listOf(p(0, 0, 4), p(1, 0, 3, 2), p(0, 4)), wide.placements)
        assertEquals(3, wide.totalRows)

        val narrow = pack(2, *spans)
        assertEquals(listOf(p(0, 0, 2), p(1, 0, 2, 2), p(3, 0)), narrow.placements)
        assertEquals(4, narrow.totalRows)
    }

    @Test
    fun `one column stacks every tile and clips widths to one`() {
        val result = pack(1, 1 to 1, 3 to 2, 1 to 1)
        assertEquals(listOf(p(0, 0), p(1, 0, 1, 2), p(3, 0)), result.placements)
        assertEquals(4, result.totalRows)
    }

    @Test
    fun `non-positive spans and columns are treated as one`() {
        val result = pack(0, 0 to 0, -2 to 1)
        assertEquals(1, result.columns)
        assertEquals(listOf(p(0, 0), p(1, 0)), result.placements)
    }

    @Test
    fun `reusing the packer gives the same result as a fresh one`() {
        val spans = arrayOf(2 to 3, 1 to 1, 3 to 1, 1 to 2, 1 to 1, 2 to 2, 1 to 1)
        // Dirty the buffer with a different, bigger pack first.
        pack(5, *Array(40) { (it % 3 + 1) to (it % 2 + 1) })
        val reused = pack(3, *spans)
        val fresh = GridPacker().pack(3, spans.toList(), { it.first }, { it.second })
        assertEquals(fresh, reused)
    }

    @Test
    fun `many tiles grow the buffer and never overlap`() {
        val spans = Array(200) { ((it * 7) % 4 + 1) to ((it * 5) % 3 + 1) }
        val result = pack(4, *spans)
        val cells = HashSet<Pair<Int, Int>>()
        result.placements.forEach { placement ->
            assertTrue(placement.endColumn <= 4)
            for (r in placement.row until placement.endRow) {
                for (c in placement.column until placement.endColumn) {
                    assertTrue("cell ($r,$c) used twice", cells.add(r to c))
                }
            }
        }
        assertEquals(result.placements.maxOf { it.endRow }, result.totalRows)
    }

    @Test
    fun `placements are deterministic`() {
        val spans = arrayOf(1 to 2, 2 to 1, 1 to 1, 3 to 1, 2 to 2)
        assertEquals(pack(4, *spans), pack(4, *spans))
    }

    @Test
    fun `earlier hole is preferred over a later one`() {
        // Row 0: A B B _, row 1: C C C _  -> the 1x1 goes to the earlier hole (0,3), not (1,3).
        val result = pack(4, 1 to 1, 2 to 1, 3 to 1, 1 to 1)
        assertEquals(listOf(p(0, 0), p(0, 1, 2), p(1, 0, 3), p(0, 3)), result.placements)
        assertFalse(result.placements.any { it.row == 1 && it.column == 3 })
    }
}
