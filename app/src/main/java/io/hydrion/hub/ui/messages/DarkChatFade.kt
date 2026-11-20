package io.hydrion.hub.ui.messages

/** Coordinates are relative to the message viewport. Stops run from the opaque edge into the chat. */
internal data class DarkChatFadeEdge(
    val guardY: Float,
    val innerY: Float,
    val stops: List<Pair<Float, Float>>,
) {
    fun endY(reveal: Float) = guardY + (innerY - guardY) * reveal.coerceIn(0f, 1f)
}

internal data class DarkChatFade(val top: DarkChatFadeEdge, val bottom: DarkChatFadeEdge)

internal fun darkChatFade(
    height: Float, headerHeight: Float, bottomBarHeight: Float, capsuleTop: Float?, capsuleBottom: Float?,
    safeTop: Float, safeBottom: Float, density: Float,
): DarkChatFade {
    val header = headerHeight.coerceIn(0f, height)
    val bar = bottomBarHeight.coerceIn(0f, height)
    val hasCapsule = capsuleTop != null && capsuleBottom != null &&
        capsuleTop >= 0f && capsuleTop < capsuleBottom && capsuleBottom <= height
    val topGuard = safeTop.coerceIn(0f, header)
    // During an IME resize the previous capsule can fall outside the new viewport.
    // The measured bar and the unconsumed navigation inset are in this frame's coordinates.
    val composerTop = if (hasCapsule) requireNotNull(capsuleTop) else height - bar
    val bottomGuard = if (hasCapsule) requireNotNull(capsuleBottom) else height - safeBottom.coerceIn(0f, bar)
    val topSpan = if (header > 0f) header - topGuard + ChatEdgeFade.BelowHeaderDp * density else 0f
    val bottomSpan = if (composerTop < height) bottomGuard - composerTop + ChatEdgeFade.AboveComposerDp * density else 0f
    // In a short viewport only the gradients contract. The solid portions stay attached to the bars.
    val scale = ChatEdgeFade.scaleToViewport((bottomGuard - topGuard).coerceAtLeast(0f), topSpan, bottomSpan, density)
    val topStops = buildList {
        add(0f to 1f)
        if (topSpan > 0f) {
            val headerStop = (header - topGuard) / topSpan
            if (headerStop > 0f) add(headerStop to ChatEdgeFade.HeaderEdgeAlpha)
            add((headerStop + (1f - headerStop) * ChatEdgeFade.TopTailStop) to ChatEdgeFade.TopTailAlpha)
        }
        add(1f to 0f)
    }
    val bottomStops = buildList {
        add(0f to 1f)
        if (bottomGuard > composerTop) add((bottomGuard - composerTop) / bottomSpan to ChatEdgeFade.ComposerEdgeAlpha)
        add(1f to 0f)
    }
    return DarkChatFade(
        DarkChatFadeEdge(topGuard, topGuard + topSpan * scale, topStops),
        DarkChatFadeEdge(bottomGuard, bottomGuard - bottomSpan * scale, bottomStops),
    )
}
