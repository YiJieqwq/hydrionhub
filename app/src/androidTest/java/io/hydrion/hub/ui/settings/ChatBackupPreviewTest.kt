package io.hydrion.hub.ui.settings

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.hydrion.hub.MainActivity
import io.hydrion.hub.R
import io.hydrion.hub.model.Conversation
import io.hydrion.hub.model.Draft
import io.hydrion.hub.model.InspectedChatArchive
import io.hydrion.hub.model.SavedWorkspace
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ChatBackupPreviewTest {
    @get:Rule val screen = createAndroidComposeRule<MainActivity>()

    @Test fun previewsCountsAndThreeTitlesAndWaitsForAnExplicitImport() {
        val longTitle = "A long synthetic conversation title ".repeat(8).trimEnd()
        val chats = listOf("First topic", longTitle, "Third topic", "Fourth topic")
            .mapIndexed { index, name -> Conversation(id = "fixture-$index", title = name) }
        val archive = InspectedChatArchive(
            workspace = SavedWorkspace(chats, mapOf(chats.first().id to Draft("Synthetic draft"))),
            blobs = mapOf("fixture-blob" to File("synthetic-media-entry")), generated = emptyMap(), formatVersion = 2,
        )
        val pending = Conversation(id = "draft-only")
        val draftsOnly = archive.copy(
            workspace = SavedWorkspace(drafts = mapOf(pending.id to Draft("Unsent")), unsent = pending),
            blobs = emptyMap(), formatVersion = 3,
        )
        val preview = mutableStateOf<InspectedChatArchive?>(archive)
        val imported = mutableListOf<InspectedChatArchive>()
        screen.activityRule.scenario.onActivity { activity ->
            activity.setContent {
                MaterialTheme {
                    Column(Modifier.width(240.dp).verticalScroll(rememberScrollState())) {
                        preview.value?.let { candidate ->
                            ChatBackupPreview(candidate, busy = false, onImport = {
                                imported += candidate
                                preview.value = null
                            }, onCancel = { preview.value = null })
                        }
                    }
                }
            }
        }

        fun text(id: Int, vararg values: Any) = screen.onNodeWithText(screen.activity.getString(id, *values))
        text(R.string.backup_chat_version, 2).assertIsDisplayed()
        text(R.string.backup_summary, 4, 1, 1).assertIsDisplayed()
        chats.take(3).forEach { screen.onNodeWithText(it.title).performScrollTo().assertIsDisplayed() }
        screen.onNodeWithText(chats.last().title).assertDoesNotExist()
        val layouts = mutableListOf<TextLayoutResult>()
        screen.onNodeWithText(longTitle).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertTrue(layouts.single().isLineEllipsized(0))
        text(R.string.backup_more_chats, 1).performScrollTo().assertIsDisplayed()
        text(android.R.string.cancel).performScrollTo().performClick()
        screen.runOnIdle {
            assertEquals(null, preview.value)
            assertTrue(imported.isEmpty())
            preview.value = draftsOnly
        }

        text(R.string.backup_chat_version, 3).assertIsDisplayed()
        text(R.string.backup_summary, 0, 1, 0).assertIsDisplayed()
        text(R.string.backup_apply).performScrollTo().assertIsEnabled().performClick()
        screen.runOnIdle { assertEquals(listOf(draftsOnly), imported) }
    }
}
