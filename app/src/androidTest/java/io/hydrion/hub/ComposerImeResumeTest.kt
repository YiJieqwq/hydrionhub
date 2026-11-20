package io.hydrion.hub

import android.view.KeyEvent
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.performTextInputSelection
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.TextRange
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ComposerImeResumeTest {
    @get:Rule val screen = createAndroidComposeRule<MainActivity>()

    private val editor get() = screen.onNode(hasContentDescription("消息输入框") and hasSetTextAction())

    private fun waitForKeyboard(visible: Boolean) {
        val view = screen.activity.window.decorView
        val ime = WindowInsetsCompat.Type.ime()
        screen.waitUntil(15_000) {
            screen.runOnUiThread {
                val insets = ViewCompat.getRootWindowInsets(view)
                view.hasWindowFocus() && insets != null && insets.isVisible(ime) == visible &&
                    (insets.getInsets(ime).bottom > insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom) == visible
            }
        }
    }

    private fun leaveAndReturn() {
        // ActivityScenario covers the real activity with its opaque test activity and then uncovers it.
        val scenario = screen.activityRule.scenario
        scenario.moveToState(Lifecycle.State.CREATED)
        assertEquals(Lifecycle.State.CREATED, scenario.state)
        scenario.moveToState(Lifecycle.State.RESUMED)
        val view = screen.activity.window.decorView
        screen.waitUntil(15_000) { screen.runOnUiThread { view.hasWindowFocus() } }
        screen.waitForIdle()
    }

    @OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
    @Test fun resumeWithOpenKeyboardKeepsEditingAtTheSameSelection() {
        editor.performTouchInput { click(center) }
        waitForKeyboard(true)
        editor.performTextReplacement("existing draft")
        val selection = TextRange(2, 5)
        editor.performTextInputSelection(selection)
        assertEquals(selection, editor.fetchSemanticsNode().config[SemanticsProperties.TextSelectionRange])

        leaveAndReturn()

        // Do not tap or perform a text action here; either one could reopen the IME and mask a regression.
        waitForKeyboard(true)
        editor.assertIsFocused().assertTextEquals("existing draft")
        assertEquals(selection, editor.fetchSemanticsNode().config[SemanticsProperties.TextSelectionRange])
    }

    @Test fun resumeAfterExplicitBackKeepsTheKeyboardClosedAndDraftParked() {
        editor.performTouchInput { click(center) }
        waitForKeyboard(true)
        editor.performTextReplacement("parked draft")
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        waitForKeyboard(false)
        editor.assertIsNotFocused()

        leaveAndReturn()

        waitForKeyboard(false)
        editor.assertIsNotFocused().assertTextEquals("parked draft")
    }
}
