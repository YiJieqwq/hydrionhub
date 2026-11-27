package io.hydrion.hub.provider

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

internal val EffortRailHitHeight = 48.dp
internal val EffortRailHeight = 26.dp

private val RailMargin = 12.dp
internal val EffortThumbRadius = 15.dp
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

private fun stopFraction(index: Int, last: Int): Float = index.toFloat() / last

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
internal fun FixedEffortTrack() {
    val colors = MaterialTheme.colorScheme
    Box(Modifier.fillMaxWidth().height(EffortRailHitHeight).testTag("chat-model-effort").padding(horizontal = RailMargin),
        contentAlignment = Alignment.Center) {
        Spacer(Modifier.fillMaxWidth().height(EffortRailHeight).background(colors.surfaceContainerHighest, CircleShape))
        Spacer(Modifier.size(EffortThumbRadius * 2).background(effortThumbColor(colors.surface.luminance() < .5f), CircleShape))
    }
}

@Composable
internal fun EffortTrack(
    cap: Capability, choices: List<String>, value: String, highestIndex: Int, accelerated: Boolean,
    onPreview: (String?) -> Unit, onChoose: (String) -> Unit,
) {
    if (choices.size < 2) {
        FixedEffortTrack()
        return
    }
    DiscreteChoiceTrack(choices, value, chatCapabilityLabel(cap), { chatOptionLabel(cap, it) }, onChoose,
        modifier = Modifier.testTag("chat-model-effort"), highestIndex = highestIndex, accelerated = accelerated, onPreview = onPreview)
}

/** One continuous rail shared by the effort chooser and the chat panel's mutually exclusive modes. */
@Composable
internal fun DiscreteChoiceTrack(
    choices: List<String>, value: String, label: String, labelFor: @Composable (String) -> String, onChoose: (String) -> Unit,
    modifier: Modifier = Modifier, enabled: Boolean = true,
    highestIndex: Int = -1, accelerated: Boolean = false, onPreview: (String?) -> Unit = {},
    segments: (@Composable (position: () -> Float?) -> Unit)? = null,
) {
    val segmented = segments != null
    val last = choices.lastIndex
    val stored = choices.indexOf(value)
    val index = stored.coerceAtLeast(0)
    val thumb = remember(choices) { EffortThumb(stopFraction(index, last)) }
    val scope = rememberCoroutineScope()
    val currentIndex by rememberUpdatedState(index)
    val preview by rememberUpdatedState(onPreview)
    val choose by rememberUpdatedState(onChoose)
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    LaunchedEffect(thumb, index) { thumb.settle(scope, stopFraction(index, last)) }

    val nearest by remember(thumb) { derivedStateOf { (thumb.position * last).roundToInt().coerceIn(0, last) } }
    val announced = if (thumb.pressed) nearest else index
    val known = stored >= 0 || !segmented || thumb.pressed
    val level = if (known) labelFor(choices[announced]) else value
    fun select(i: Int) {
        thumb.settle(scope, stopFraction(i, last))
        if ((stored < 0 && segmented) || i != currentIndex) choose(choices[i])
    }

    val touch = if (!enabled) Modifier else Modifier.pointerInput(thumb, stored, rtl, segmented) {
        val pass = if (segmented) PointerEventPass.Initial else PointerEventPass.Main
        awaitEachGesture {
            val down = awaitFirstDown(pass = pass)
            val inset = if (segmented) size.width / (2f * choices.size) else (RailMargin + EffortThumbRadius).toPx()
            if (!segmented) down.consume()
            var previewIndex = -1
            fun follow(x: Float) {
                thumb.position = effortPointerFraction(x, size.width.toFloat(), inset, rtl, thumb.position)
                val i = (thumb.position * last).roundToInt()
                if (previewIndex != i) {
                    previewIndex = i
                    preview(choices[i])
                }
            }

            if (!segmented) {
                thumb.grab()
                follow(down.position.x)
            }
            try {
                while (true) {
                    val change = awaitPointerEvent(pass).changes.firstOrNull { it.id == down.id } ?: break
                    // A cancelled pointer is delivered as consumed. Never commit the last preview in that case.
                    if (change.isConsumed) break
                    if (!thumb.pressed) {
                        // Leave an ordinary tap to the label, and a vertical drag to the scrolling panel.
                        if (!segmented || !change.pressed) break
                        val distance = change.position - down.position
                        if (kotlin.math.abs(distance.y) > viewConfiguration.touchSlop &&
                            kotlin.math.abs(distance.y) > kotlin.math.abs(distance.x)) break
                        if (kotlin.math.abs(distance.x) <= viewConfiguration.touchSlop) continue
                        thumb.grab()
                    }
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

    Box(modifier.fillMaxWidth().heightIn(min = EffortRailHitHeight).then(touch).semantics {
        contentDescription = label
        stateDescription = level
        if (known) progressBarRangeInfo = ProgressBarRangeInfo(announced.toFloat(), 0f..last.toFloat(), last - 1)
        if (!enabled) disabled() else setProgress { requested ->
            val next = accessibleEffortIndex(requested, currentIndex, last)
            if (known && next == currentIndex) return@setProgress false
            select(next)
            true
        }
    }, contentAlignment = Alignment.Center) {
        if (segments != null) {
            segments { if (known) thumb.position else null }
        } else {
            val thumbScale = animateFloatAsState(
                targetValue = if (thumb.pressed) 1.16f else 1f,
                animationSpec = if (thumb.pressed) tween(140, easing = CubicBezierEasing(.2f, .9f, .3f, 1.2f))
                    else tween(340, easing = CubicBezierEasing(.34f, 1.56f, .64f, 1f)),
                label = "effortThumbScale",
            )
            Box(Modifier.fillMaxWidth().padding(horizontal = RailMargin), contentAlignment = Alignment.Center) {
                EffortRail({ thumb.position }, { thumbScale.value }, choices, highestIndex, index, accelerated)
            }
        }
    }
}
