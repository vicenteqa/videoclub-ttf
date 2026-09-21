package com.videoclub.app.ui

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Up and down between the shelves of a long page, moved by hand rather than left to the focus
 * search.
 *
 * The focus search can only find something that exists, and in a lazy list the shelf above is
 * usually not on screen and therefore not composed. What it finds instead is the tab strip, which is
 * how pressing `up` in the middle of the page jumped straight to the top of it.
 *
 * So the shelf is scrolled to first — that is what brings it into being — and only then handed the
 * cursor. Asking a whole shelf for the cursor rather than a card means the row decides which card
 * gets it, which is the row's business and not this one's.
 *
 * Off either end [step] returns false and the search takes over, so `up` from the first shelf still
 * reaches the tabs, which is exactly where it should go.
 *
 * Every shelf takes [keys] as its modifier and [requester] as the focus it hands to the card it
 * wants the cursor on. The page's `LazyColumn` takes [page].
 */
@Stable
class ShelfCursor internal constructor(
    val page: LazyListState,
    private val scope: CoroutineScope,
    private val requesters: List<FocusRequester>,
    private val perch: Int
) {

    fun requester(index: Int): FocusRequester = requesters[index]

    fun step(from: Int, delta: Int): Boolean {
        val target = from + delta
        if (target !in requesters.indices) return false
        scope.launch {
            page.animateScrollToItem(target, -perch)
            // A shelf that has just been scrolled into existence is not placed until the next
            // frame, and asking an unplaced node for the cursor throws. Two frames is plenty.
            repeat(3) { attempt ->
                if (runCatching { requesters[target].requestFocus() }.isSuccess) return@launch
                if (attempt < 2) withFrameNanos { }
            }
        }
        return true
    }

    fun keys(index: Int): Modifier = Modifier.onPreviewKeyEvent { event ->
        if (event.type != KeyEventType.KeyDown) {
            false
        } else {
            when (event.key) {
                Key.DirectionUp -> step(index, -1)
                Key.DirectionDown -> step(index, 1)
                else -> false
            }
        }
    }
}

/**
 * A [ShelfCursor] for a page of [shelves] rows, one per item of its `LazyColumn`, in order.
 *
 * The shelf under the cursor always lands the same distance below the top of the page. A fixed
 * place is the whole point: scrolling each shelf to the very top — which is what this once did —
 * meant every press moved the page by a different amount depending on where things happened to be,
 * and the page appeared to lurch at random. Landing every shelf in the same spot makes one press
 * mean one shelf, and leaves the bottom of the shelf above showing, which is what tells you which
 * way you just came from.
 */
@Composable
fun rememberShelfCursor(shelves: Int): ShelfCursor {
    val page = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val perch = with(LocalDensity.current) { (LocalSkin.current.rowGap * 2).roundToPx() }
    return remember(shelves, page, scope, perch) {
        ShelfCursor(page, scope, List(shelves) { FocusRequester() }, perch)
    }
}
