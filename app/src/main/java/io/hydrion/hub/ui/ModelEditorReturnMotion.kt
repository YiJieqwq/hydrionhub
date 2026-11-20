package io.hydrion.hub.ui

import android.animation.ValueAnimator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.platform.LocalWindowInfo
import io.hydrion.hub.provider.ChatPickerEasing
import io.hydrion.hub.provider.ChatPickerTransitionMillis
import kotlin.coroutines.coroutineContext

@Stable
internal class ModelEditorReturnMotion(returning: Boolean) {
    var contentProgress by mutableFloatStateOf(if (returning) 0f else 1f)
        internal set
    var composerLandingProgress by mutableFloatStateOf(if (returning) 0f else 1f)
        internal set
    var holdCollapsed by mutableStateOf(returning)
        internal set
}

@Composable
internal fun rememberModelEditorReturnMotion(
    returning: Boolean,
    canLand: () -> Boolean,
    composerProgress: () -> Float,
    composerSettled: () -> Boolean,
    onLanded: () -> Unit,
    onImeFallback: () -> Unit,
    onFinished: () -> Unit,
): ModelEditorReturnMotion {
    val motion = remember(returning) { ModelEditorReturnMotion(returning) }
    val ready by rememberUpdatedState(canLand)
    val progress by rememberUpdatedState(composerProgress)
    val settled by rememberUpdatedState(composerSettled)
    val land by rememberUpdatedState(onLanded)
    val fallback by rememberUpdatedState(onImeFallback)
    val finish by rememberUpdatedState(onFinished)
    val frames = remember(motion) { ModelEditorReturnFrames() }
    val windowFocused = LocalWindowInfo.current.isWindowFocused
    LaunchedEffect(motion, windowFocused) {
        if (!returning || !windowFocused) return@LaunchedEffect
        val durationScale = coroutineContext[MotionDurationScale]
        var previous = withFrameNanos { it }
        while (frames.phase != ModelEditorReturnFrames.Phase.Complete) {
            val now = withFrameNanos { it }
            val scale = durationScale?.scaleFactor ?: if (ValueAnimator.areAnimatorsEnabled()) 1f else 0f
            val elapsed = (now - previous) / 1_000_000f
            previous = now
            val event = frames.advance(elapsed, ChatPickerTransitionMillis * scale, ready(), progress(), settled())
            motion.composerLandingProgress = ChatPickerEasing.transform(frames.composerLandingProgress)
            motion.contentProgress = ChatPickerEasing.transform(frames.contentProgress)
            motion.holdCollapsed = frames.phase == ModelEditorReturnFrames.Phase.Landing
            when (event) {
                ModelEditorReturnFrames.Event.Landed -> land()
                ModelEditorReturnFrames.Event.ImeFallback -> fallback()
                ModelEditorReturnFrames.Event.Finished -> finish()
                ModelEditorReturnFrames.Event.None -> Unit
            }
        }
    }
    return motion
}
