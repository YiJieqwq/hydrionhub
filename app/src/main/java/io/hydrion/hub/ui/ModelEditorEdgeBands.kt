package io.hydrion.hub.ui

import io.hydrion.hub.ui.messages.ChatEdgeFade
import io.hydrion.hub.ui.messages.chatFadeBands

/** All values are pixels in the untransformed transition host. */
internal data class ModelEditorEdgeBands(
    val topEnd: Float,
    val bottomStart: Float,
    val bottomStop: Float,
    val bottomEdgeStop: Float,
)

internal fun modelEditorEdgeBands(
    safeTop: Float, safeBottom: Float, dockHeight: Float,
    composerTop: Float?, composerBottom: Float?, density: Float,
): ModelEditorEdgeBands {
    val available = (safeBottom - safeTop).coerceAtLeast(0f)
    val bars = chatFadeBands(available, 0f, dockHeight, composerTop?.minus(safeTop),
        composerBottom?.minus(safeTop), density)
    val topReach = ChatEdgeFade.BelowHeaderDp * density
    val scale = ChatEdgeFade.scaleToViewport(available, topReach, bars.bottomLength, density)
    return ModelEditorEdgeBands(safeTop + topReach * scale, safeBottom - bars.bottomLength * scale,
        bars.capsuleTopStop, bars.capsuleBottomStop)
}

internal fun exposedWorkspaceTop(height: Float, safeTop: Float, safeBottom: Float, cover: Float): Float =
    (height * cover).coerceIn(safeTop, maxOf(safeTop, safeBottom))
