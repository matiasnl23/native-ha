package com.matiasnl.hakiosk.ui.dashboard.grid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GridMetricsTest {

    // 4 columns x 3 rows in 1000x700 with a 20px gutter:
    // cellWidth = (1000 - 5*20) / 4 = 225, cellHeight = (700 - 4*20) / 3 ≈ 206.67, row pitch ≈ 226.67.
    private val metrics = GridMetrics(columns = 4, rows = 3, viewportWidth = 1000, viewportHeight = 700, gutter = 20f)

    private fun p(row: Int, column: Int = 0, colSpan: Int = 1, rowSpan: Int = 1) =
        GridPlacement(row, column, colSpan, rowSpan)

    @Test
    fun `cells and gutters fill the viewport exactly`() {
        assertEquals(225f, metrics.cellWidth, 0.001f)
        assertEquals(20, metrics.left(0))
        assertEquals(265, metrics.left(1))
        // Last column ends one gutter before the right edge.
        assertEquals(980, metrics.right(3, 1))
        // Last visible row ends one gutter before the bottom edge.
        assertEquals(680, metrics.bottom(2, 1))
    }

    @Test
    fun `spans include the gutters they cover`() {
        val wide = p(row = 0, column = 1, colSpan = 2, rowSpan = 2)
        assertEquals(225 * 2 + 20, metrics.width(wide))
        assertEquals(metrics.bottom(1, 1) - metrics.top(0), metrics.height(wide))
    }

    @Test
    fun `adjacent tiles leave exactly one gutter between them`() {
        val a = p(row = 0, column = 0)
        val b = p(row = 0, column = 1)
        assertEquals(20, metrics.left(b) - (metrics.left(a) + metrics.width(a)))
    }

    @Test
    fun `content height equals the viewport while rows fit`() {
        assertEquals(700, metrics.contentHeight(0))
        assertEquals(700, metrics.contentHeight(3))
    }

    @Test
    fun `content height grows by one row pitch per extra row`() {
        assertEquals(927, metrics.contentHeight(4)) // 20 + 4 * 226.67
        assertEquals(1153, metrics.contentHeight(5))
    }

    @Test
    fun `tiny viewport never yields negative cells`() {
        val tiny = GridMetrics(columns = 12, rows = 12, viewportWidth = 100, viewportHeight = 50, gutter = 12f)
        assertEquals(0f, tiny.cellWidth)
        assertEquals(0f, tiny.cellHeight)
        assertEquals(0, tiny.width(p(0)))
    }

    @Test
    fun `rows inside the viewport are visible without scrolling`() {
        assertTrue(metrics.isVisible(p(0), scrollOffset = 0))
        assertTrue(metrics.isVisible(p(2), scrollOffset = 0))
        assertFalse(metrics.isVisible(p(3), scrollOffset = 0))
    }

    @Test
    fun `a partially scrolled-in tile is visible`() {
        // Row 3 starts at 20 + 3 * 226.67 = 700: one pixel of scroll brings it in.
        assertFalse(metrics.isVisible(p(3), scrollOffset = 0))
        assertTrue(metrics.isVisible(p(3), scrollOffset = 1))
    }

    @Test
    fun `a tile scrolled past the top is not visible`() {
        val bottomOfRow0 = metrics.bottom(0, 1)
        assertTrue(metrics.isVisible(p(0), scrollOffset = bottomOfRow0 - 1))
        assertFalse(metrics.isVisible(p(0), scrollOffset = bottomOfRow0))
    }

    @Test
    fun `a tall tile stays visible while any part of it is on screen`() {
        val tall = p(row = 0, rowSpan = 5)
        assertTrue(metrics.isVisible(tall, scrollOffset = 900))
        assertFalse(metrics.isVisible(tall, scrollOffset = metrics.bottom(0, 5)))
    }
}
