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
import kotlin.math.abs
import kotlin.math.roundToInt

internal const val ToolsPageTravelMillis = 320

// A symmetric curve leaves half the visible height and panel travel for the second half.
private val ToolsTravelEasing = CubicBezierEasing(.2f, 0f, .8f, 1f)

/** Reversible tool-style page travel. The surface and composer read the same sampled progress. */
@Stable
internal class PanelTravelClock {
    private var timeline by mutableFloatStateOf(0f)
    val progress: Float get() = ToolsTravelEasing.transform(timeline)
    val atStart by derivedStateOf { timeline == 0f }
    val atEnd by derivedStateOf { timeline == 1f }

    suspend fun animateTo(open: Boolean, stillRequested: () -> Boolean) {
        val end = if (open) 1f else 0f
        if (timeline == end) return
        val millis = (ToolsPageTravelMillis * abs(end - timeline)).roundToInt()
        animate(timeline, end, animationSpec = tween(millis, easing = LinearEasing)) { value, _ ->
            // A Back event may arrive before composition cancels the previous leg.
            // Never publish another tick for an intent the user has already reversed.
            if (stillRequested()) timeline = value
        }
    }

    fun snapOpen() { timeline = 1f }
}

@Stable
internal class ToolsPageState {
    private val clock = PanelTravelClock()
    var targetOpen by mutableStateOf(false)
        private set
    val progress: Float get() = clock.progress
    val active by derivedStateOf { targetOpen || !clock.atStart }
    val interactive by derivedStateOf { targetOpen && clock.atEnd }

    fun open() { targetOpen = true }
    fun close() { targetOpen = false }
    suspend fun animateToTarget(open: Boolean) = clock.animateTo(open) { targetOpen == open }

}

@Composable
internal fun rememberToolsPageState(conversationId: String): ToolsPageState {
    val state = remember(conversationId) { ToolsPageState() }
    val open = state.targetOpen
    LaunchedEffect(state, open) { state.animateToTarget(open) }
    return state
}
