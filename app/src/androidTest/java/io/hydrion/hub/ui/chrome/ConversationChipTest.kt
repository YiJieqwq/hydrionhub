package io.hydrion.hub.ui.chrome

import android.content.res.Configuration
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import io.hydrion.hub.MainActivity
import io.hydrion.hub.model.Conversation
import io.hydrion.hub.model.Mode
import io.hydrion.hub.model.newId
import io.hydrion.hub.runtime.ProotEnvironment
import io.hydrion.hub.ui.theme.HydrionTheme
import java.util.Locale
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ConversationChipTest {
    @get:Rule val screen = createAndroidComposeRule<MainActivity>()

    @Test fun localizesMissingBindingAndReservesTheRingAndCountWhenSwitchingThreads() {
        val longName = "A long environment name for a shared writing workspace"
        val env = ProotEnvironment(newId(), newId(), name = longName)
        val bound = Conversation(title = "Bound chat", environmentId = env.id)
        val empty = Conversation(title = "Unbound chat")
        val chat = mutableStateOf(empty)
        val language = mutableStateOf(Locale.ENGLISH)
        screen.activityRule.scenario.onActivity { activity ->
            activity.setContent {
                val config = remember(language.value) { Configuration(activity.resources.configuration).apply { setLocale(language.value) } }
                val context = remember(config) { activity.createConfigurationContext(config) }
                CompositionLocalProvider(LocalContext provides context, LocalConfiguration provides config) {
                    HydrionTheme {
                        Box(Modifier.width(330.dp)) {
                            val current = chat.value
                            Header(mode = Mode.Chat, locked = true, title = current.title, pinned = false,
                                contextUsage = ContextUsage(123400, 150000), contextWindow = remember { ContextWindowState() },
                                environmentName = conversationEnvironmentName(current) { env.takeIf { e -> e.id == it } },
                                onMode = {}, onDrawer = {}, onNew = {}, onRename = {}, onPin = {}, onFind = {}, onDelete = {})
                        }
                    }
                }
            }
        }
        screen.onNodeWithText("Not bound", useUnmergedTree = true).assertIsDisplayed()
        screen.runOnIdle { language.value = Locale.SIMPLIFIED_CHINESE }
        screen.onNodeWithText("未绑定", useUnmergedTree = true).assertIsDisplayed()
        screen.runOnIdle { chat.value = bound }
        screen.onNodeWithText(bound.title, useUnmergedTree = true).assertIsDisplayed()
        val name = screen.onNodeWithText(longName, useUnmergedTree = true).assertIsDisplayed()
        val layouts = mutableListOf<TextLayoutResult>()
        name.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertTrue(layouts.single().isLineEllipsized(0))
        val ring = screen.onNodeWithTag("context-usage-ring", useUnmergedTree = true).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val count = screen.onNodeWithText("123.4k", useUnmergedTree = true).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertTrue(name.fetchSemanticsNode().boundsInRoot.right <= ring.left && ring.right <= count.left)
        screen.runOnIdle { chat.value = empty }
        screen.onNodeWithText(longName, useUnmergedTree = true).assertDoesNotExist()
        screen.onNodeWithText("未绑定", useUnmergedTree = true).assertIsDisplayed()
    }
}
