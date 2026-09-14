package com.matiasnl.hakiosk.ui.dashboard.grid

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Real gesture test for edit-mode reordering: the tiles are `clickable`, exactly like the edit-mode
 * cells on the dashboard, and the pointer long-presses one tile and drags it over another.
 */
@RunWith(AndroidJUnit4::class)
class DashboardGridReorderTest {

    @get:Rule
    val rule = createComposeRule()

    @Test
    fun longPressAndDragReordersClickableTiles() {
        val items = mutableStateListOf("a", "b", "c", "d")
        val moves = mutableListOf<Pair<Int, Int>>()
        var clicks = 0

        rule.setContent {
            val snapshot = items.toList()
            val packing = remember(snapshot) { GridPacker().pack(2, snapshot, { 1 }, { 1 }) }
            DashboardGrid(
                items = snapshot,
                itemKey = { it },
                packing = packing,
                visibleRows = 2,
                modifier = Modifier.size(400.dp),
                draggableCount = snapshot.size,
                onMove = { from, to ->
                    moves += from to to
                    items.add(to, items.removeAt(from))
                },
            ) { item, _, _ ->
                Box(Modifier.fillMaxSize().testTag("tile-$item").clickable { clicks++ })
            }
        }

        // Press and hold, then let the long-press timeout elapse before moving, as a real finger does.
        var longPressMillis = 0L
        rule.onNodeWithTag("tile-a").performTouchInput {
            longPressMillis = viewConfiguration.longPressTimeoutMillis
            down(center)
        }
        rule.mainClock.advanceTimeBy(longPressMillis + 300)
        rule.waitForIdle()

        // Drag from tile "a" (top-left) to the center of tile "d" (bottom-right) in small steps.
        rule.onNodeWithTag("tile-a").performTouchInput {
            val target = Offset(width * 1.5f, height * 1.5f)
            val steps = 30
            for (step in 1..steps) {
                moveTo(center + (target - center) * (step / steps.toFloat()))
            }
        }
        rule.waitForIdle()
        rule.onNodeWithTag("tile-a").performTouchInput { up() }
        rule.waitForIdle()

        assertTrue("expected the drag to reorder tiles, moves=$moves", moves.isNotEmpty())
        assertEquals("a drop must not also click the tile", 0, clicks)
    }
}
