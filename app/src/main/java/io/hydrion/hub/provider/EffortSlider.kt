package io.hydrion.hub.provider

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import io.hydrion.hub.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private val RailMargin = 12.dp
private val ThumbRadius = 15.dp
private val SnapSpec = tween<Float>(180, easing = CubicBezierEasing(.2f, .8f, .2f, 1f))

internal fun effortPointerFraction(x: Float, width: Float, inset: Float, rtl: Boolean, fallback: Float): Float {
    val travel = width - 2 * inset
    if (travel <= 0f || !x.isFinite()) return fallback
    val fraction = ((x - inset) / travel).coerceIn(0f, 1f)
    return if (rtl) 1f - fraction else fraction
}

internal fun accessibleEffortIndex(requested: Float, current: Int, last: Int): Int {
    if (!requested.isFinite()) return current
    val target = requested.coerceIn(0f, last.toFloat())
    // With only two stops Compose reports zero interior steps. TalkBack then adjusts by 1/20
    // of the range, which would round back to the current endpoint indefinitely.
    if (last == 1 && target != current.toFloat()) return 1 - current
    return target.roundToInt()
}

private fun stopFraction(index: Int, last: Int): Float = if (last == 0) .5f else index.toFloat() / last

private class EffortThumb(initial: Float) {
    var position by mutableFloatStateOf(initial)
    var pressed by mutableStateOf(false)
        private set
    private var destination = initial
    private var settling: Job? = null

    fun grab() {
        settling?.cancel()
        pressed = true
    }

    fun settle(scope: CoroutineScope, target: Float) {
        if (!pressed && destination == target) return
        settling?.cancel()
        pressed = false
        destination = target
        if (position != target) settling = scope.launch {
            animate(position, target, animationSpec = SnapSpec) { value, _ -> position = value }
        }
    }
}

@Composable
internal fun EffortTrack(
    cap: Capability, choices: List<String>, value: String, highestIndex: Int, accelerated: Boolean,
    onPreview: (String?) -> Unit, onChoose: (String) -> Unit,
) {
    if (choices.isEmpty()) return
    val last = choices.lastIndex
    val index = choices.indexOf(value).coerceAtLeast(0)
    val thumb = remember(choices) { EffortThumb(stopFraction(index, last)) }
    val scope = rememberCoroutineScope()
    val currentIndex by rememberUpdatedState(index)
    val preview by rememberUpdatedState(onPreview)
    val choose by rememberUpdatedState(onChoose)
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val thumbScale = animateFloatAsState(
        targetValue = if (thumb.pressed) 1.16f else 1f,
        animationSpec = if (thumb.pressed) tween(140, easing = CubicBezierEasing(.2f, .9f, .3f, 1.2f))
            else tween(340, easing = CubicBezierEasing(.34f, 1.56f, .64f, 1f)),
        label = "effortThumbScale",
    )
    LaunchedEffect(thumb, index) { thumb.settle(scope, stopFraction(index, last)) }

    val nearest by remember(thumb) { derivedStateOf { (thumb.position * last).roundToInt().coerceIn(0, last) } }
    val announced = if (thumb.pressed) nearest else index
    val level = chatOptionLabel(cap, choices[announced])
    val description = if (last == 0) stringResource(R.string.chat_controls_fixed, level) else level
    val label = chatCapabilityLabel(cap)
    fun select(i: Int) {
        thumb.settle(scope, stopFraction(i, last))
        if (i != currentIndex) choose(choices[i])
    }

    val touch = if (last == 0) Modifier else Modifier.pointerInput(thumb, index, rtl) {
        val inset = (RailMargin + ThumbRadius).toPx()
        awaitEachGesture {
            val down = awaitFirstDown()
            down.consume()
            var previewIndex = -1
            fun follow(x: Float) {
                thumb.position = effortPointerFraction(x, size.width.toFloat(), inset, rtl, thumb.position)
                val i = (thumb.position * last).roundToInt()
                if (previewIndex != i) {
                    previewIndex = i
                    preview(choices[i])
                }
            }

            thumb.grab()
            try {
                follow(down.position.x)
                while (true) {
                    val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                    // A cancelled pointer is delivered as consumed. Never commit the last preview in that case.
                    if (change.isConsumed || !thumb.pressed) break
                    follow(change.position.x)
                    change.consume()
                    if (!change.pressed) {
                        select(previewIndex)
                        break
                    }
                }
            } finally {
                if (thumb.pressed) thumb.settle(scope, stopFraction(currentIndex, last))
                preview(null)
            }
        }
    }

    Box(Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("chat-model-effort").then(touch).semantics {
        contentDescription = label
        stateDescription = description
        if (last > 0) {
            progressBarRangeInfo = ProgressBarRangeInfo(announced.toFloat(), 0f..last.toFloat(), last - 1)
            setProgress { requested ->
                val next = accessibleEffortIndex(requested, currentIndex, last)
                if (next == currentIndex) return@setProgress false
                select(next)
                true
            }
        }
    }, contentAlignment = Alignment.Center) {
        Box(Modifier.fillMaxWidth().padding(horizontal = RailMargin), contentAlignment = Alignment.Center) {
            EffortRail({ thumb.position }, { thumbScale.value }, choices, highestIndex, index, accelerated)
        }
    }
}
