package io.hydrion.hub.ui.messages

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.hydrion.hub.MainActivity
import io.hydrion.hub.model.Attachment
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MessageAttachmentTest {
    @get:Rule val screen = createAndroidComposeRule<MainActivity>()

    @Test fun allFilenamesRemainVisibleAndOnlyAnOpenableContentUriIsClickable() {
        fun attachment(id: String, uri: String?) = Attachment(id, "$id.txt", uri = uri, mimeType = "text/plain")
        val openable = attachment("openable", "content://fixture.invalid/doc/1")
        val unavailable = listOf(
            attachment("missing", null),
            attachment("empty", ""),
            attachment("blank", "  \t"),
            attachment("unsupported", "https://fixture.invalid/doc/2"),
            attachment("no-authority", "content:///doc/3"),
        )
        screen.activityRule.scenario.onActivity { activity ->
            activity.setContent {
                MaterialTheme {
                    Column { (listOf(openable) + unavailable).forEach { MessageAttachment(it, query = "") } }
                }
            }
        }

        screen.onNodeWithText(openable.name).assertIsDisplayed().assertHasClickAction()
        unavailable.forEach { screen.onNodeWithText(it.name).assertIsDisplayed().assertHasNoClickAction() }
    }
}
