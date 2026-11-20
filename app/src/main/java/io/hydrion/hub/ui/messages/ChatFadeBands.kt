package io.hydrion.hub.ui.messages

internal data class ChatFadeBands(
    val topLength: Float,
    val bottomLength: Float,
    val headerStop: Float,
    val capsuleTopStop: Float,
    val capsuleBottomStop: Float,
)

/** Portion of the chat edge fades visible between the floating header and composer. */
internal object ChatEdgeFade {
    const val BelowHeaderDp = 56f
    const val AboveComposerDp = 28f
    const val ScrollRevealDp = 24f
    const val HeaderEdgeAlpha = .52f
    const val TopTailStop = .70f
    const val TopTailAlpha = .10f
    const val ComposerEdgeAlpha = .45f

    fun scaleToViewport(height: Float, topLength: Float, bottomLength: Float, density: Float): Float {
        val clearHeight = minOf(96f * density, height / 3f)
        return ((height - clearHeight) / (topLength + bottomLength).coerceAtLeast(1f)).coerceIn(0f, 1f)
    }
}

/** Lengths are viewport pixels. Stops are relative to their own gradient. */
internal fun chatFadeBands(
    height: Float, headerHeight: Float, bottomBarHeight: Float,
    capsuleTop: Float?, capsuleBottom: Float?, density: Float,
): ChatFadeBands {
    val header = headerHeight.coerceIn(0f, height)
    // Global placement can briefly lag the IME's newest viewport. Use the measured
    // Scaffold bar until the visible capsule has a valid frame in this viewport.
    val valid = capsuleTop != null && capsuleBottom != null &&
        capsuleTop >= 0f && capsuleBottom <= height && capsuleBottom > capsuleTop
    val top = if (valid) requireNotNull(capsuleTop) else height - bottomBarHeight.coerceIn(0f, height)
    val bottom = if (valid) requireNotNull(capsuleBottom) else height
    val aboveComposer = ChatEdgeFade.AboveComposerDp * density
    val topLength = if (header > 0f) header + ChatEdgeFade.BelowHeaderDp * density else 0f
    val bottomLength = if (top < height) height - top + aboveComposer else 0f
    // Reserve a clear reading band when the keyboard or a multiline draft takes most of the window.
    val scale = ChatEdgeFade.scaleToViewport(height, topLength, bottomLength, density)
    return ChatFadeBands(
        topLength * scale, bottomLength * scale,
        if (topLength > 0f) header / topLength else 0f,
        if (bottomLength > 0f) aboveComposer / bottomLength else 0f,
        if (bottomLength > 0f) 1f - (height - bottom) / bottomLength else 0f,
    )
}
