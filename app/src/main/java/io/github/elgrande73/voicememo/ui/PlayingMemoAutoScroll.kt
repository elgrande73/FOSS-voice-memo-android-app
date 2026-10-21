package io.github.elgrande73.voicememo.ui

import android.os.SystemClock
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay

/**
 * Scroll decisions for keeping the playing memo visible in the history list. Pure arithmetic so it can be tested for
 * any screen size; positions are pixels in the list's coordinate space (as in LazyListLayoutInfo).
 */
object PlayingMemoScroll {

    /**
     * Pixels to scroll (positive = towards later items) so the item at [itemTop] with height [itemSize] is fully inside
     * the viewport [viewportStart, viewportEnd) with [margin] around it. 0 when it is already fully visible, so a
     * visible card never moves. An item taller than the viewport is aligned at its bottom, where the play/stop
     * controls are.
     */
    fun delta(itemTop: Int, itemSize: Int, viewportStart: Int, viewportEnd: Int, margin: Int): Int {
        val itemBottom = itemTop + itemSize
        if (itemTop >= viewportStart && itemBottom <= viewportEnd) return 0
        val top = viewportStart + margin
        val bottom = viewportEnd - margin
        return when {
            itemSize > bottom - top -> itemBottom - bottom
            itemTop < top -> itemTop - top
            else -> itemBottom - bottom
        }
    }

    /** No automatic scrolling for this long after the user last dragged the list. */
    const val USER_IDLE_MS = 2_500L
}

/**
 * Keeps the memo at [playingIndex] fully visible while [enabled] (playback mode Autoplay): when playback moves to
 * another memo, the list scrolls just enough to show the whole card including its play/stop button. Waits while the
 * user is scrolling (and shortly after), so it never fights a gesture; does nothing when the card is already visible.
 *
 * @param playingKey key of the playing item (changes trigger a scroll), or null when nothing plays.
 */
@Composable
fun PlayingMemoAutoScroll(listState: LazyListState, playingKey: Any?, playingIndex: Int, enabled: Boolean, marginPx: Int) {
    val dragged by listState.interactionSource.collectIsDraggedAsState()
    var lastUserScrollAt by remember { mutableLongStateOf(0L) }
    LaunchedEffect(dragged) { if (dragged || lastUserScrollAt > 0L) lastUserScrollAt = SystemClock.uptimeMillis() }
    val currentIndex by rememberUpdatedState(playingIndex)

    LaunchedEffect(playingKey, enabled) {
        if (!enabled || playingKey == null) return@LaunchedEffect
        // Never interrupt the user: wait until they have stopped scrolling for a moment
        while (dragged || SystemClock.uptimeMillis() - lastUserScrollAt < PlayingMemoScroll.USER_IDLE_MS && lastUserScrollAt > 0L) {
            delay(200)
        }
        val index = currentIndex
        if (index < 0) return@LaunchedEffect
        if (listState.layoutInfo.visibleItemsInfo.none { it.key == playingKey }) {
            // Far away (long list): jump close to it first, then fine-tune below
            listState.scrollToItem(index)
        }
        val info = listState.layoutInfo
        val item = info.visibleItemsInfo.firstOrNull { it.key == playingKey } ?: return@LaunchedEffect
        val delta = PlayingMemoScroll.delta(item.offset, item.size, info.viewportStartOffset, info.viewportEndOffset, marginPx)
        if (delta != 0) listState.animateScrollBy(delta.toFloat())
    }
}
