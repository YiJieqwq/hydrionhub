package io.hydrion.hub.ui.messages

import android.content.ClipData
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.hydrion.hub.MainActivity
import io.hydrion.hub.R
import io.hydrion.hub.model.Attachment
import io.hydrion.hub.model.Conversation
import io.hydrion.hub.model.HubViewModel
import io.hydrion.hub.model.Message
import io.hydrion.hub.model.Role
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MessageSourceCopyTest {
    @get:Rule val screen = createAndroidComposeRule<MainActivity>()

    @OptIn(ExperimentalTestApi::class)
    @Test fun sourceCopyWritesOnlyRawMarkdownAndKeepsTheBodySelectable() {
        val source = "# Heading\n\n```kotlin\n    val n = 1  \n```\n"
        val message = Message(id = "reply", role = Role.Assistant, text = source,
            thought = "private reasoning", prompt = "input prompt",
            attachments = listOf(Attachment("file", "notes.txt")))
        val conversation = Conversation(id = "fixture", messages = listOf(message))
        val sheet = mutableStateOf<TurnSheet?>(TurnSheet.More)
        val writes = mutableListOf<AnnotatedString>()
        val clipboard = object : ClipboardManager {
            override fun setText(annotatedString: AnnotatedString) { writes += annotatedString }
            override fun getText(): AnnotatedString? = writes.lastOrNull()
        }
        screen.activityRule.scenario.onActivity { activity ->
            val vm = ViewModelProvider(activity)[HubViewModel::class.java]
            activity.setContent {
                MaterialTheme {
                    CompositionLocalProvider(LocalClipboardManager provides clipboard) {
                        sheet.value?.let { ChatTurnOverlay(message, conversation, vm, false, it) { next -> sheet.value = next } }
                    }
                }
            }
        }

        val selectText = screen.activity.getString(R.string.turn_select_copy)
        val copySource = screen.activity.getString(R.string.turn_copy_markdown_source)
        val ok = screen.activity.getString(android.R.string.ok)
        screen.onNode(hasText(selectText) and hasClickAction()).performScrollTo().performClick()
        screen.onNodeWithContentDescription(copySource).performClick()
        screen.runOnIdle {
            assertEquals(listOf(AnnotatedString(source)), writes)
            assertEquals(TurnSheet.SelectText, sheet.value)
        }

        val body = screen.onNodeWithText(source).assertIsDisplayed()
        val layouts = mutableListOf<TextLayoutResult>()
        body.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val word = layouts.single().getBoundingBox(source.indexOf("Heading") + 2).center
        val systemClipboard = screen.activity.getSystemService(android.content.ClipboardManager::class.java)
        screen.runOnIdle { systemClipboard.setPrimaryClip(ClipData.newPlainText("fixture", "")) }
        body.performTouchInput { longClick(word) }
        body.performKeyInput { keyDown(Key.CtrlLeft); pressKey(Key.C); keyUp(Key.CtrlLeft) }
        screen.runOnIdle {
            // The sheet owns a new Android window with its own platform selection toolbar.
            assertEquals("Heading", systemClipboard.primaryClip?.getItemAt(0)?.text?.toString())
        }
        screen.onNode(hasText(ok) and hasClickAction()).performClick()
        body.assertDoesNotExist()
        screen.runOnIdle {
            assertEquals(null, sheet.value)
            assertEquals(listOf(AnnotatedString(source)), writes)
        }
    }
}
