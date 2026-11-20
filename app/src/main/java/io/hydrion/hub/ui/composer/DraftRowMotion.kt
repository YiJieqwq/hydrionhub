package io.hydrion.hub.ui.composer

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember

/** Ordinary draft/attachment reveal. Search pages use the panel timeline separately. */
internal class DraftRowMotion(content: Boolean) {
    private val value = Animatable(if (content) 1f else 0f)
    val fraction get() = value.value

    suspend fun moveTo(content: Boolean, heldFolded: Boolean) {
        val target = if (content && !heldFolded) 1f else 0f
        if (heldFolded) value.snapTo(0f)
        else if (fraction != target) value.animateTo(target, tween(33, easing = LinearEasing))
    }
}

@Composable
internal fun rememberDraftRowMotion(content: Boolean, heldFolded: Boolean): DraftRowMotion {
    val motion = remember { DraftRowMotion(content && !heldFolded) }
    LaunchedEffect(motion, content, heldFolded) { motion.moveTo(content, heldFolded) }
    return motion
}
