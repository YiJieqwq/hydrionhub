package io.hydrion.hub.ui.composer

import io.hydrion.hub.ui.ChatPanelMotionMillis
import org.junit.Assert.assertEquals
import org.junit.Test

class ComposerRowTargetTest {
    private fun target(draft: Boolean = false, search: Boolean = false, list: Boolean = false,
                       ime: Boolean = false, held: Boolean = false) =
        composerRowTarget(held, draft, search, list, ime)

    @Test fun hiddenImeSearchUsesFullTravelAndReversesOverOnlyTheRemainingDistance() {
        val browse = target(list = true)
        val search = target(search = true, list = true)
        assertEquals(ChatPanelMotionMillis, search.travelMillisFrom(browse, 0f))
        assertEquals(ChatPanelMotionMillis, browse.travelMillisFrom(search, 1f))
        assertEquals(ChatPanelMotionMillis / 4, browse.travelMillisFrom(search, .25f))
        assertEquals(ChatPanelMotionMillis / 2, search.travelMillisFrom(browse, .5f))
        assertEquals(ChatPanelMotionMillis, search.travelMillisFrom(target(), 0f))
    }

    @Test fun ordinaryDraftContentAndActualKeyboardKeepTheirOriginalTimings() {
        val draft = target(draft = true)
        val browse = target(list = true)
        val search = target(search = true, list = true)
        assertEquals(33, draft.travelMillisFrom(browse, 0f))
        assertEquals(33, browse.travelMillisFrom(draft, 1f))
        val withKeyboard = target(search = true, list = true, ime = true)
        assertEquals(33, withKeyboard.travelMillisFrom(browse, 0f))
        assertEquals(33, target(list = true, ime = true).travelMillisFrom(withKeyboard, 1f))
        assertEquals(draft, target(draft = true, search = true, list = true))
        assertEquals(0, target(held = true).travelMillisFrom(search, .25f))
        assertEquals(33, target().travelMillisFrom(search, 1f))
    }
}
