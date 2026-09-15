package com.matiasnl.hakiosk.ui.dashboard.tiles

import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.draggable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import com.matiasnl.hakiosk.ui.dashboard.tiles.light.BrightnessSwipeState

/**
 * An entity tile's gestures outside edit mode: tap runs [onClick] when [isActionable], long press runs
 * [onLongClick] when [hasDetails], and, with a [brightnessSwipe], a horizontal swipe changes brightness.
 *
 * How they coexist: the long press only fires if the finger stays within touch slop for the timeout,
 * and the swipe only starts once it moves past that slop horizontally — whichever happens first wins.
 * The swipe consumes its moves, which cancels the pending tap/long press, and since the tile sits inside
 * the pager, it also keeps the pager from turning the page. Vertical moves are left to the grid's scroll.
 */
fun Modifier.entityTileGestures(
    isActionable: Boolean,
    hasDetails: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    brightnessSwipe: BrightnessSwipeState? = null,
    reverseSwipeDirection: Boolean = false,
): Modifier {
    val swipe = if (brightnessSwipe == null) {
        Modifier
    } else {
        Modifier
            .onSizeChanged { brightnessSwipe.widthPx = it.width.toFloat() }
            // Observes each press without consuming it, to re-enable a swipe a long press suppressed.
            .pointerInput(brightnessSwipe) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    brightnessSwipe.onPress()
                }
            }
            .draggable(
                state = brightnessSwipe.draggableState,
                orientation = Orientation.Horizontal,
                reverseDirection = reverseSwipeDirection,
                onDragStarted = { brightnessSwipe.onDragStarted() },
                onDragStopped = { brightnessSwipe.onDragStopped() },
            )
    }
    val press = when {
        hasDetails -> Modifier.combinedClickable(
            onClick = { if (isActionable) onClick() },
            onLongClick = {
                brightnessSwipe?.onLongPress()
                onLongClick()
            },
        )
        isActionable -> Modifier.clickable(onClick = onClick)
        else -> Modifier
    }
    return this.then(swipe).then(press)
}
