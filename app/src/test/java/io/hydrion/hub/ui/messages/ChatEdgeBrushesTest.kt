package io.hydrion.hub.ui.messages

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test

class ChatEdgeBrushesTest {
    @Test fun composerTravelOnlyRebuildsTheEdgesWhoseGeometryChanges() {
        val brushes = ChatEdgeBrushes(Color.Black)
        val expanded = chatFadeBands(1500f, 204f, 320f, 1180f, 1463f, 3f)
        val compact = chatFadeBands(1500f, 204f, 320f, 1315f, 1463f, 3f)
        val top = brushes.top(expanded)
        val bottom = brushes.bottom(expanded, 1500f)
        assertSame(top, brushes.top(compact))
        assertNotSame(bottom, brushes.bottom(compact, 1500f))
        val compactBottom = brushes.bottom(compact, 1500f)
        assertSame(compactBottom, brushes.bottom(compact.copy(headerStop = .4f), 1500f))

        val cramped = chatFadeBands(750f, 204f, 320f, 430f, 713f, 3f)
        val crampedCompact = chatFadeBands(750f, 204f, 320f, 565f, 713f, 3f)
        assertNotSame(brushes.top(cramped), brushes.top(crampedCompact))
    }

    @Test fun changedStopsAbsoluteCoordinatesAndBackgroundUseTheNewGradient() {
        val bands = chatFadeBands(1500f, 204f, 320f, 1180f, 1463f, 3f)
        val brushes = ChatEdgeBrushes(Color.Black)
        val top = brushes.top(bands)
        assertNotEquals(top, brushes.top(bands.copy(headerStop = .2f)))
        val bottom = brushes.bottom(bands, 1500f)
        assertNotEquals(bottom, brushes.bottom(bands, 1450f))
        assertNotEquals(bottom, brushes.bottom(bands.copy(capsuleTopStop = .2f), 1500f))
        assertNotEquals(bottom, brushes.bottom(bands.copy(capsuleBottomStop = 1f), 1500f))
        val light = ChatEdgeBrushes(Color.White)
        assertNotEquals(top, light.top(bands))
        assertNotEquals(bottom, light.bottom(bands, 1500f))
    }
}
