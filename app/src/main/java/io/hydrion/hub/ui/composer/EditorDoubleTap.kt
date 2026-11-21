package io.hydrion.hub.ui.composer

import android.view.ViewConfiguration
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.motionEventSpy
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.TextFieldValue

internal class EditorTapSequence(
    private val minInterval: Long,
    private val maxInterval: Long,
    private val longPress: Long,
    private val touchSlop: Float,
    private val doubleTapSlop: Float,
) {
    private data class Tap(val time: Long, val position: Offset, val draft: TextFieldValue)
    private var down: Tap? = null
    private var previous: Tap? = null

    fun begin(time: Long, position: Offset, draft: TextFieldValue) {
        down = Tap(time, position, draft)
    }

    fun move(time: Long, position: Offset) {
        val start = down ?: return
        if (time - start.time >= longPress || (position - start.position).getDistance() > touchSlop) cancel()
    }

    private fun completedTap(time: Long, position: Offset): Tap? {
        move(time, position)
        return down.also { down = null }
    }

    fun endSingle(time: Long, position: Offset): Boolean = completedTap(time, position) != null

    fun end(time: Long, position: Offset): TextFieldValue? {
        val start = completedTap(time, position) ?: return null
        val first = previous
        if (first != null && start.time - first.time in minInterval..maxInterval &&
            (first.position - start.position).getDistance() <= doubleTapSlop &&
            first.draft.text == start.draft.text
        ) {
            previous = null
            return first.draft
        }
        // Measure the interval from first up to second down, but compare both down positions.
        previous = start.copy(time = time)
        return null
    }

    fun cancel() {
        down = null
        previous = null
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun Modifier.dismissOnEditorTap(
    owner: Any?,
    onSingleTap: (() -> Unit)?,
    onFirstDown: () -> TextFieldValue,
    onDoubleTap: (TextFieldValue) -> Unit,
): Modifier {
    if (owner == null) return this
    val singleTap by rememberUpdatedState(onSingleTap)
    val draft by rememberUpdatedState(onFirstDown)
    val dismiss by rememberUpdatedState(onDoubleTap)
    var coordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    var multiplePointers by remember { mutableStateOf(false) }
    val doubleTapSlop = ViewConfiguration.get(LocalContext.current).scaledDoubleTapSlop.toFloat()
    // Compose can route a second finger to a sibling and omit it from this node's changes.
    val editor = onGloballyPositioned { coordinates = it }.motionEventSpy { multiplePointers = it.pointerCount > 1 }
    return editor.pointerInput(owner, doubleTapSlop) {
        val taps = with(viewConfiguration) {
            EditorTapSequence(doubleTapMinTimeMillis, doubleTapTimeoutMillis, longPressTimeoutMillis, touchSlop, doubleTapSlop)
        }
        // Compose also hit-tests expanded touch bounds. Only the visible editor belongs to this gesture.
        fun windowPosition(point: Offset): Offset? {
            val bounds = coordinates?.takeIf { it.isAttached } ?: return null
            if (point.x < 0f || point.y < 0f || point.x >= bounds.size.width || point.y >= bounds.size.height) return null
            return bounds.localToWindow(point)
        }
        awaitEachGesture {
            val first = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            val start = windowPosition(first.position)
            val primaryPointer = first.type == PointerType.Touch || singleTap != null &&
                (first.type == PointerType.Stylus || first.type == PointerType.Mouse && currentEvent.buttons.isPrimaryPressed)
            if (first.isConsumed || multiplePointers || currentEvent.changes.size != 1 || !primaryPointer || start == null) {
                taps.cancel()
                return@awaitEachGesture
            }
            taps.begin(first.uptimeMillis, start, draft())
            do {
                val change = awaitPointerEvent(PointerEventPass.Initial).changes.singleOrNull()
                val point = change?.let { windowPosition(it.position) }
                if (change == null || multiplePointers || change.id != first.id || change.isConsumed || point == null) {
                    taps.cancel()
                    break
                }
                for (sample in change.historical) {
                    val past = windowPosition(sample.position)
                    if (past == null) taps.cancel() else taps.move(sample.uptimeMillis, past)
                }
                if (change.pressed) {
                    taps.move(change.uptimeMillis, point)
                } else {
                    val onTap = singleTap
                    if (onTap != null) {
                        // Observe the release; BasicTextField still places the caret and handles the IME.
                        if (taps.endSingle(change.uptimeMillis, point)) onTap()
                    } else {
                        val saved = taps.end(change.uptimeMillis, point)
                        if (saved != null && saved.text == draft().text) {
                            change.consume()
                            dismiss(saved)
                        }
                    }
                    break
                }
            } while (true)
        }
    }
}
