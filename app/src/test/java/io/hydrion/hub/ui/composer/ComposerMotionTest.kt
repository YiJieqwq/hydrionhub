package io.hydrion.hub.ui.composer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ComposerMotionTest {
    @Test fun dismissalTracksTheInsetWithoutRoundingAtIntermediateHeights() {
        listOf(800 to 1f, 799 to .99875f, 600 to .75f, 400 to .5f, 200 to .25f, 0 to 0f)
            .forEach { (height, expected) ->
                assertEquals(expected, composerImeProgress(height, 800, 0)!!, .0001f)
            }
    }

    @Test fun openingUsesInsetsAndKeyboardResizesDoNotDismissTheField() {
        assertEquals(.25f, composerImeProgress(200, 0, 800)!!, .0001f)
        assertEquals(1f, composerImeProgress(800, 0, 800)!!, 0f)
        assertNull(composerImeProgress(600, 800, 600))
        assertNull(composerImeProgress(0, 0, 0))
    }
}
