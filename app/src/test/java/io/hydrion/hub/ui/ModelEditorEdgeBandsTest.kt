package io.hydrion.hub.ui

import io.hydrion.hub.ui.messages.ChatEdgeFade
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelEditorEdgeBandsTest {
    @Test fun workspaceBandReachesTheRealComposerAndExcludesTheKeyboard() {
        // The 960px window has a status inset and a visible 320px keyboard.
        val bands = modelEditorEdgeBands(24f, 640f, 85f, 550f, 620f, 1f)
        assertEquals(80f, bands.topEnd, 0f)
        assertEquals(522f, bands.bottomStart, 0f)
        assertEquals(550f, bands.bottomStart + (640f - bands.bottomStart) * bands.bottomStop, .001f)
        assertEquals(620f, bands.bottomStart + (640f - bands.bottomStart) * bands.bottomEdgeStop, .001f)
    }

    @Test fun workspaceNeverPaintsOverTheMovingPageOrSystemBarsInEitherDirection() {
        val progress = listOf(0f, .02f, .25f, .6f, .95f, 1f, .55f, .21f, 0f)
        for (cover in progress) {
            val start = exposedWorkspaceTop(960f, 24f, 640f, cover)
            assertEquals((960f * cover).coerceIn(24f, 640f), start, 0f)
            if (start < 640f) assertTrue(start >= 960f * cover && start >= 24f)
        }
    }

    @Test fun staleCapsuleFallsBackAndShortViewportRetainsAClearCenter() {
        val fallback = modelEditorEdgeBands(42f, 132f, 74f, null, null, 3f)
        assertEquals(fallback, modelEditorEdgeBands(42f, 132f, 74f, 124f, 288f, 3f))
        assertTrue(fallback.bottomStart - fallback.topEnd >= 30f - .001f)
        val reach = ChatEdgeFade.BelowHeaderDp + ChatEdgeFade.AboveComposerDp
        val contentScale = ChatEdgeFade.scaleToViewport(45f, ChatEdgeFade.BelowHeaderDp, ChatEdgeFade.AboveComposerDp, 1f)
        assertEquals(15f, 45f - reach * contentScale, .001f)
    }
}
