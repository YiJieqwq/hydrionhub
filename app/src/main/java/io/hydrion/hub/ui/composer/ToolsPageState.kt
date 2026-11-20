package io.hydrion.hub.ui.composer

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.input.TextFieldValue
import io.hydrion.hub.ui.ChatPanelMotionMillis
import kotlin.math.abs
import kotlin.math.roundToInt

internal const val ToolsPageTravelMillis = ChatPanelMotionMillis

// A symmetric curve leaves half the visible height and panel travel for the second half.
private val ToolsTravelEasing = CubicBezierEasing(.2f, 0f, .8f, 1f)

/** One reversible timeline for the tools surface, capsule, and both travelling buttons. */
@Stable
internal class ToolsPageState {
    private val timeline = Animatable(0f)
    var targetOpen by mutableStateOf(false)
        private set
    val progress: Float get() = ToolsTravelEasing.transform(timeline.value)
    val active by derivedStateOf { targetOpen || timeline.value > 0f }
    val interactive by derivedStateOf { targetOpen && timeline.value == 1f }
    var query by mutableStateOf(TextFieldValue())

    fun open() {
        if (!active) clearQuery()
        targetOpen = true
    }
    fun close() { targetOpen = false }
    fun clearQuery() { query = TextFieldValue() }

    suspend fun animateToTarget(open: Boolean) {
        val end = if (open) 1f else 0f
        val millis = (ToolsPageTravelMillis * abs(end - timeline.value)).roundToInt()
        timeline.animateTo(end, tween(millis, easing = LinearEasing))
        if (!open && !targetOpen) clearQuery()
    }
}

@Composable
internal fun rememberToolsPageState(conversationId: String): ToolsPageState {
    val state = remember(conversationId) { ToolsPageState() }
    val open = state.targetOpen
    LaunchedEffect(state, open) { state.animateToTarget(open) }
    return state
}
