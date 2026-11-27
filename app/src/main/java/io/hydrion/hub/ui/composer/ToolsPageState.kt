package io.hydrion.hub.ui.composer

import androidx.compose.animation.core.animate
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.MotionDurationScale
import kotlin.coroutines.coroutineContext
import kotlin.math.abs
import kotlin.math.roundToInt

internal const val ToolsPageTravelMillis = 320

// A symmetric curve leaves half the visible height and panel travel for the second half.
private val ToolsTravelEasing = CubicBezierEasing(.2f, 0f, .8f, 1f)

/** Reversible tool-style page travel. The surface and composer read the same sampled progress. */
@Stable
internal class PanelTravelClock {
    private var timeline by mutableFloatStateOf(0f)
    private var heldProgress by mutableStateOf<Float?>(null)
    val progress: Float get() = heldProgress ?: ToolsTravelEasing.transform(timeline)
    val held get() = heldProgress != null
    val atStart by derivedStateOf { timeline == 0f }
    val atEnd by derivedStateOf { !held && timeline == 1f }

    // Only the first few percent of shell height overlap the final IME travel.
    // Insets are sampled from the originating editor. The model search has its own IME.
    private val overlap = .18f
    var entryImeHeight = 0
        private set
    private var imeHeight: () -> Int = { 0 }
    var passedIme by mutableStateOf(false)
        private set
    val nearEditor get() = timeline <= overlap
    val contentStart: Float get() = if (entryImeHeight > 0) ToolsTravelEasing.transform(overlap) else 0f
    val contentProgress: Float get() {
        val start = contentStart
        return ((progress - start) / (1f - start)).coerceIn(0f, 1f)
    }

    fun prepareEditor(raised: Boolean, inset: () -> Int) {
        imeHeight = inset
        entryImeHeight = if (raised) inset() else 0
        passedIme = false
    }

    fun hold() { heldProgress = progress }

    fun seek(heightFraction: Float) { heldProgress = heightFraction.coerceIn(0f, 1f) }

    fun release() {
        val heightFraction = heldProgress ?: return
        // Convert back to the animation timeline once, at release. Pointer updates stay linear.
        var low = 0f
        var high = 1f
        repeat(16) {
            val mid = (low + high) / 2f
            if (ToolsTravelEasing.transform(mid) < heightFraction) low = mid else high = mid
        }
        timeline = when {
            heightFraction <= 0f -> 0f
            heightFraction >= 1f -> 1f
            else -> (low + high) / 2f
        }
        heldProgress = null
    }

    suspend fun animateTo(open: Boolean, stillRequested: () -> Boolean) {
        fun live() = !held && stillRequested()
        val end = if (open) 1f else 0f
        if (!live() || timeline == end) return
        if (entryImeHeight == 0 || open && passedIme) {
            val millis = (ToolsPageTravelMillis * abs(end - timeline)).roundToInt()
            animate(timeline, end, animationSpec = tween(millis, easing = LinearEasing)) { value, _ ->
                if (live()) timeline = value
            }
            return
        }

        // Sample actual window travel and advance the same timeline without restarting a
        // tween for every inset. A mid-flight reversal starts at the last painted value.
        val scale = coroutineContext[MotionDurationScale]?.scaleFactor ?: 1f
        var lastFrame = withFrameNanos { it }
        var lastChange = lastFrame
        var lastInset = imeHeight()
        while (live() && timeline != end) {
            withFrameNanos { frame ->
                if (!live()) return@withFrameNanos
                val inset = imeHeight()
                if (inset != lastInset) {
                    lastInset = inset
                    lastChange = frame
                }
                val remaining = inset.toFloat() / entryImeHeight
                val overlapLimit = overlap * ((.20f - remaining) / .17f).coerceIn(0f, 1f)
                // If the OS declines a show/hide, keep the editor and the panel reachable.
                val stalled = frame - lastChange >= 1_000_000_000L
                if (open && (remaining <= .03f || stalled)) passedIme = true
                val limit = when {
                    open && passedIme -> 1f
                    !open && (remaining >= .20f || stalled) -> 0f
                    else -> overlapLimit
                }
                val step = if (scale == 0f) 1f else (frame - lastFrame) / (ToolsPageTravelMillis * 1_000_000f * scale)
                timeline = if (open) (timeline + step).coerceAtMost(maxOf(timeline, limit))
                    else (timeline - step).coerceAtLeast(minOf(timeline, limit))
                lastFrame = frame
            }
        }
    }

    fun snapOpen() { timeline = 1f }
}

@Stable
internal class ToolsPageState {
    val clock = PanelTravelClock()
    var targetOpen by mutableStateOf(false)
        private set
    val progress: Float get() = clock.progress
    val active by derivedStateOf { targetOpen || !clock.atStart }
    val interactive by derivedStateOf { targetOpen && clock.atEnd }

    fun open() { targetOpen = true }
    fun close() {
        targetOpen = false
        clock.release()
    }
    suspend fun animateToTarget(open: Boolean) = clock.animateTo(open) { targetOpen == open }

}

@Composable
internal fun rememberToolsPageState(conversationId: String): ToolsPageState {
    val state = remember(conversationId) { ToolsPageState() }
    val open = state.targetOpen
    LaunchedEffect(state, open, state.clock.held) { if (!state.clock.held) state.animateToTarget(open) }
    return state
}
