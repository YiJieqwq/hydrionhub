package io.hydrion.hub.ui.sidebar

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import kotlin.math.abs

internal enum class SearchDrag { Pending, Vertical, Horizontal, Switched }

internal fun SearchDrag.advance(dx: Float, dy: Float, slop: Float, threshold: Float, blank: Boolean): SearchDrag {
    if (this == SearchDrag.Vertical || this == SearchDrag.Switched) return this
    if (this == SearchDrag.Pending) {
        if (abs(dy) >= slop && abs(dy) >= abs(dx)) return SearchDrag.Vertical
        if (abs(dx) < slop || abs(dx) <= abs(dy)) return SearchDrag.Pending
    }
    return if (blank && abs(dx) >= threshold) SearchDrag.Switched else SearchDrag.Horizontal
}

/** Reserve horizontal gestures for the field. With text, give the editor first chance to use them. */
@Composable
internal fun Modifier.drawerSearchSwipe(onToggle: (() -> Unit)?, blank: Boolean): Modifier {
    if (onToggle == null) return this
    val toggle = rememberUpdatedState(onToggle)
    return pointerInput(blank) {
        val pass = if (blank) PointerEventPass.Initial else PointerEventPass.Main
        val threshold = 18.dp.toPx()
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            var state = SearchDrag.Pending
            do {
                val event = awaitPointerEvent(pass)
                val pointer = event.changes.firstOrNull { it.id == down.id } ?: break
                if (event.changes.size != 1) break
                val delta = pointer.position - down.position
                val next = state.advance(delta.x, delta.y, viewConfiguration.touchSlop, threshold, blank)
                if (next == SearchDrag.Vertical) break
                if (next != SearchDrag.Pending && (blank || pointer.pressed)) pointer.consume()
                if (next == SearchDrag.Switched && state != next) toggle.value()
                state = next
            } while (pointer.pressed)
        }
    }
}
