package io.hydrion.hub.ui.messages

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/** Each visible edge reuses its gradient until that edge's own rendering inputs change. */
internal class ChatEdgeBrushes(private val background: Color) {
    private val clear = background.copy(alpha = 0f)
    private var topBands: ChatFadeBands? = null
    private var bottomBands: ChatFadeBands? = null
    private var bottomViewportHeight = 0f
    private lateinit var topGradient: Brush
    private lateinit var bottomGradient: Brush

    fun top(bands: ChatFadeBands): Brush {
        val previous = topBands
        if (previous == null || previous.topLength != bands.topLength || previous.headerStop != bands.headerStop) {
            val header = bands.headerStop
            topGradient = Brush.verticalGradient(
                0f to background, header * .45f to background.copy(alpha = .94f),
                header to background.copy(alpha = ChatEdgeFade.HeaderEdgeAlpha),
                (header + (1f - header) * ChatEdgeFade.TopTailStop) to background.copy(alpha = ChatEdgeFade.TopTailAlpha),
                1f to clear, endY = bands.topLength,
            )
            topBands = bands
        }
        return topGradient
    }

    fun bottom(bands: ChatFadeBands, viewportHeight: Float): Brush {
        val previous = bottomBands
        if (previous == null || bottomViewportHeight != viewportHeight || previous.bottomLength != bands.bottomLength ||
            previous.capsuleTopStop != bands.capsuleTopStop || previous.capsuleBottomStop != bands.capsuleBottomStop) {
            val stops = buildList {
                add(0f to clear)
                add(bands.capsuleTopStop to background.copy(alpha = ChatEdgeFade.ComposerEdgeAlpha))
                add(bands.capsuleBottomStop to background.copy(alpha = .88f))
                if (bands.capsuleBottomStop < 1f) add(1f to background)
            }
            bottomGradient = Brush.verticalGradient(
                *stops.toTypedArray(), startY = viewportHeight - bands.bottomLength, endY = viewportHeight,
            )
            bottomBands = bands
            bottomViewportHeight = viewportHeight
        }
        return bottomGradient
    }
}
