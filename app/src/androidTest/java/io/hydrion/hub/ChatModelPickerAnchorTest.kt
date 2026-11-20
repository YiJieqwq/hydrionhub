package io.hydrion.hub

import android.view.View
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.hydrion.hub.model.HubViewModel
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ChatModelPickerAnchorTest {
    @get:Rule val screen = createAndroidComposeRule<MainActivity>()

    @Test fun cardAndListsShareTheEdgeAboveAMultilineComposer() {
        val vm = screen.runOnIdle { ViewModelProvider(screen.activity)[HubViewModel::class.java] }
        val previous = screen.runOnIdle { vm.state.value.draft.text }
        try {
            screen.runOnIdle { vm.setDraft("first line\nsecond line\nthird line") }
            screen.onNodeWithTag("chat-model-trigger").performClick()
            val composer = screen.onNodeWithTag("composer-capsule", useUnmergedTree = true)
                .fetchSemanticsNode().boundsInRoot
            val density = screen.activity.resources.displayMetrics.density
            val rtl = screen.activity.resources.configuration.layoutDirection == View.LAYOUT_DIRECTION_RTL
            fun checkPage(tag: String): Float {
                val page = screen.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
                assertEquals(if (rtl) composer.left else composer.right, if (rtl) page.left else page.right, 2f)
                assertEquals(11.5f * density, composer.top - page.bottom, 2f)
                return page.width
            }
            val width = checkPage("chat-model-card")
            screen.onNodeWithTag("chat-model-select").performClick()
            assertEquals(width, checkPage("chat-model-list"), 1f)
            screen.onNodeWithTag("chat-model-provider-trigger").performClick()
            assertEquals(width, checkPage("chat-provider-list"), 1f)
        } finally {
            screen.runOnIdle { vm.setDraft(previous) }
        }
    }
}
