package io.hydrion.hub.ui

import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer

/** The measured layer includes the status-bar inset. Its Scaffold slot keeps its full height. */
internal fun Modifier.chatPageHeader(progress: () -> Float) = graphicsLayer {
    val travel = progress().coerceIn(0f, 1f)
    translationY = -size.height * travel
    alpha = 1f - travel
}
