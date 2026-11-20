package io.hydrion.hub.ui.composer

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import io.hydrion.hub.ui.ChatPanelMotionMillis
import kotlin.math.abs
import kotlin.math.roundToInt

@Stable
internal class ComposerHintMotion(initial: ComposerRowTarget, searching: Boolean) {
    var row by mutableFloatStateOf(initial.fraction)
        private set
    var search by mutableFloatStateOf(if (searching) 1f else 0f)
        private set
    private var previousRow = initial

    suspend fun moveTo(target: ComposerRowTarget, searching: Boolean) {
        val startRow = row
        val startSearch = search
        val endSearch = if (searching) 1f else 0f
        val rowMillis = target.travelMillisFrom(previousRow, startRow)
        val searchMillis = (ChatPanelMotionMillis * abs(endSearch - startSearch)).roundToInt()
        previousRow = target

        if (rowMillis == 0) row = target.fraction
        if (searchMillis == 0) search = endSearch
        val duration = maxOf(rowMillis, searchMillis)
        if (duration == 0) return

        // Both consumers see the same frame time. An ordinary draft row can finish early
        // while a parked search and its hint travel together over their remaining distance.
        animate(0f, duration.toFloat(), animationSpec = tween(duration, easing = LinearEasing)) { time, _ ->
            if (rowMillis > 0) row = sample(startRow, target.fraction, time, rowMillis)
            if (searchMillis > 0) search = sample(startSearch, endSearch, time, searchMillis)
        }
    }

    private fun sample(start: Float, end: Float, time: Float, duration: Int): Float =
        if (time >= duration) end else start + (end - start) * (time / duration)
}

@Composable
internal fun rememberComposerHintMotion(target: ComposerRowTarget, searching: Boolean): ComposerHintMotion {
    val motion = remember { ComposerHintMotion(target, searching) }
    LaunchedEffect(motion, target, searching) { motion.moveTo(target, searching) }
    return motion
}
