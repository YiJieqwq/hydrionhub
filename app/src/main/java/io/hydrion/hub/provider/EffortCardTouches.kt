package io.hydrion.hub.provider

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput

/** The card and its workspace observe the same dispatch. Neither consumes or redispatches it. */
internal class EffortCardTouches {
    private var startedInCard = false

    fun card(modifier: Modifier) = modifier.pointerInput(this) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            startedInCard = true
        }
    }

    fun workspace(modifier: Modifier, picker: ChatModelPickerState) = modifier.pointerInput(this, picker) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            if (picker.page != ChatModelPickerState.Page.Controls) return@awaitEachGesture
            // Initial passes through the parent before the real shell/content hit path.
            startedInCard = false
            awaitPointerEvent(PointerEventPass.Final)
            if (startedInCard) return@awaitEachGesture
            do {
                val event = awaitPointerEvent(PointerEventPass.Final)
                val pointer = event.changes.singleOrNull() ?: break
                if (pointer.id != down.id || (pointer.position - down.position).getDistance() > viewConfiguration.touchSlop ||
                    pointer.uptimeMillis - down.uptimeMillis > viewConfiguration.longPressTimeoutMillis) break
                if (!pointer.pressed) {
                    // The target's normal click already ran on Main, including any navigation.
                    if (event.type == PointerEventType.Release) picker.dismissEffort()
                    break
                }
            } while (true)
        }
    }
}
