package io.hydrion.hub

import androidx.activity.compose.setContent
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.hydrion.hub.ui.composer.ComposerMorph
import io.hydrion.hub.ui.composer.ComposerMotionInput
import io.hydrion.hub.ui.composer.rememberComposerShape
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ComposerImeDismissTest {
    @get:Rule val screen = createAndroidComposeRule<MainActivity>()

    @Test fun losingFocusWaitsForImeTravelBeforeMorphingTheCapsule() {
        val input = mutableStateOf(ComposerMotionInput(800, 800, 800, editing = true, hardwareOnly = false))
        lateinit var shape: State<Float>
        screen.runOnUiThread { screen.activity.setContent { shape = rememberComposerShape { input.value } } }
        screen.waitForIdle()
        val clock = screen.mainClock
        clock.autoAdvance = false
        try {
            clock.advanceTimeUntil(300) { shape.value == 1f }
            screen.runOnUiThread { input.value = input.value.copy(editing = false) }
            // The feature has taken focus, but Android hasn't delivered any keyboard motion yet.
            clock.advanceTimeBy(96)
            assertEquals(1f, screen.runOnUiThread { shape.value }, 0f)
            screen.runOnUiThread { input.value = input.value.copy(target = 0) }
            clock.advanceTimeBy(32)
            assertEquals(1f, screen.runOnUiThread { shape.value }, 0f)

            // Lose the animation bounds partway through. Shape must still follow the physical IME.
            screen.runOnUiThread { input.value = input.value.copy(height = 600, source = 0) }
            clock.advanceTimeUntil(100) { shape.value == .5f }
            screen.runOnUiThread { input.value = input.value.copy(height = 400) }
            clock.advanceTimeUntil(100) { shape.value == 0f }

            // Moving focus to a second editor can retain the inset indefinitely.
            screen.runOnUiThread { input.value = input.value.copy(height = 800, source = 800, target = 800, editing = true) }
            clock.advanceTimeUntil(200) { shape.value == 1f }
            screen.runOnUiThread { input.value = input.value.copy(editing = false) }
            clock.advanceTimeUntil(ComposerMorph.FollowAfterBlur.IdleTimeoutMillis + 200) { shape.value == 0f }
            assertEquals(800, input.value.height)
        } finally {
            clock.autoAdvance = true
        }
    }
}
