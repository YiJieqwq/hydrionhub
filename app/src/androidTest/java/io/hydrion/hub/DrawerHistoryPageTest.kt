package io.hydrion.hub

import android.view.View
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.hydrion.hub.model.Conversation
import io.hydrion.hub.model.LocalAssistant
import io.hydrion.hub.ui.sidebar.DrawerActions
import io.hydrion.hub.ui.sidebar.DrawerHistoryPage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DrawerHistoryPageTest {
    @get:Rule val screen = createAndroidComposeRule<MainActivity>()

    private val older = Conversation(id = "older", title = "Needle", updatedAt = 1_000)
    private val newer = older.copy(id = "newer", updatedAt = 2_000)
    private val foreign = older.copy(id = "foreign", assistantId = "other")
    private val unrelated = older.copy(id = "unrelated", title = "Different")
    private var history by mutableStateOf(listOf(older, newer, foreign, unrelated))
    private val opened = mutableListOf<String>()
    private val renamed = mutableListOf<String>()
    private val deleted = mutableListOf<Set<String>>()

    private fun showHistory() {
        val actions = DrawerActions(
            hasDraft = { false },
            pin = { ids, pin -> history = history.map { if (it.id in ids) it.copy(pinned = pin) else it } },
            move = { _, _ -> }, duplicate = { _, _ -> },
            delete = { ids -> deleted += ids; history = history.filterNot { it.id in ids } },
            copyAssistant = { _, _ -> false }, groupAssistant = { _, _ -> false },
            canRemoveAssistant = { false }, removeAssistant = {},
        )
        screen.runOnUiThread {
            screen.activity.setContent {
                MaterialTheme {
                    DrawerHistoryPage(
                        history = history, currentId = newer.id, assistant = LocalAssistant.Default,
                        multiple = true, actions = actions, onBack = {}, onOpen = { opened += it },
                        onRename = { renamed += it }, onPin = { id ->
                            history = history.map { if (it.id == id) it.copy(pinned = !it.pinned) else it }
                        },
                    )
                }
            }
        }
    }

    private fun label(id: Int) = screen.activity.getString(id)
    private fun row(id: String) = screen.onNodeWithTag("history-row-$id")
    private fun button(id: Int) = screen.onNode(hasText(label(id)) and hasClickAction())

    private fun tapRowAction(id: String, action: Int) {
        val label = label(action)
        val control = (hasText(label) or hasContentDescription(label)) and hasClickAction()
        screen.onNode(control and hasAnyAncestor(hasTestTag("history-row-$id"))).performTouchInput { click() }
    }

    private fun dragRow(id: String, from: Float, to: Float) {
        val rtl = screen.activity.resources.configuration.layoutDirection == View.LAYOUT_DIRECTION_RTL
        row(id).performTouchInput {
            fun x(fraction: Float) = width * (if (rtl) 1f - fraction else fraction)
            swipe(Offset(x(from), centerY), Offset(x(to), centerY), durationMillis = 450)
        }
    }

    @Test fun tappingPinAndUnpinReordersTheCardWithoutOpeningIt() {
        showHistory()
        fun top(id: String) = row(id).fetchSemanticsNode().boundsInRoot.top
        assertTrue(top(newer.id) < top(older.id))
        tapRowAction(older.id, R.string.drawer_pin)
        assertTrue(top(older.id) < top(newer.id))
        tapRowAction(older.id, R.string.drawer_unpin)
        assertTrue(top(newer.id) < top(older.id))
        screen.runOnIdle { assertTrue(opened.isEmpty()) }
    }

    @Test fun longPressMenuRenamesPinsAndAsksBeforeDeletingTheConversation() {
        showHistory()
        fun openMenu() = row(older.id).performTouchInput { longClick(center) }
        openMenu()
        button(R.string.drawer_rename).performClick()
        screen.runOnIdle { assertEquals(listOf(older.id), renamed) }

        openMenu()
        val menuPin = hasText(label(R.string.drawer_pin)) and
            hasAnySibling(hasText(label(R.string.drawer_rename))) and hasClickAction()
        screen.onNode(menuPin).performClick()
        screen.runOnIdle { assertTrue(history.single { it.id == older.id }.pinned) }

        openMenu()
        button(R.string.drawer_delete).performClick()
        button(R.string.settings_cancel).assertIsDisplayed().performClick()
        screen.onNodeWithTag("drawer-history-page").assertIsDisplayed()
        row(older.id).assertIsDisplayed()
        screen.runOnIdle {
            assertTrue(opened.isEmpty())
            assertTrue(deleted.isEmpty())
        }
    }

    @Test fun movingThePendingConversationOutOfScopeDiscardsItsConfirmation() {
        showHistory()
        dragRow(older.id, .85f, .15f)
        button(R.string.settings_cancel).assertIsDisplayed()
        screen.runOnIdle {
            history = history.map { if (it.id == older.id) it.copy(assistantId = foreign.assistantId) else it }
        }
        row(older.id).assertDoesNotExist()
        button(R.string.settings_cancel).assertDoesNotExist()

        screen.onNodeWithContentDescription(label(R.string.drawer_all_owners)).performClick()
        row(older.id).assertIsDisplayed()
        button(R.string.settings_cancel).assertDoesNotExist()
        screen.runOnIdle { assertTrue(deleted.isEmpty()) }
    }

    @Test fun swipeRequiresConfirmationAndKeepsTheQueryAndOwnerScope() {
        showHistory()
        row(foreign.id).assertDoesNotExist()
        screen.onNodeWithContentDescription(label(R.string.drawer_all_owners)).performClick()
        screen.onNodeWithContentDescription(label(R.string.drawer_search)).performClick()
        val field = screen.onNode(hasSetTextAction())
        field.performTextReplacement("needle")
        row(foreign.id).assertIsDisplayed()
        row(unrelated.id).assertDoesNotExist()

        dragRow(older.id, .55f, .50f)
        button(R.string.settings_cancel).assertDoesNotExist()
        dragRow(older.id, .15f, .85f)
        button(R.string.settings_cancel).assertDoesNotExist()

        dragRow(older.id, .85f, .15f)
        button(R.string.settings_cancel).assertIsDisplayed().performClick()
        field.assertTextContains("needle")
        row(older.id).assertIsDisplayed()
        row(foreign.id).assertIsDisplayed()
        screen.runOnIdle { assertTrue(deleted.isEmpty()) }

        dragRow(older.id, .85f, .15f)
        screen.runOnIdle { assertTrue(deleted.isEmpty()) }
        button(R.string.drawer_delete).performClick()
        row(older.id).assertDoesNotExist()
        field.assertTextContains("needle")
        row(newer.id).assertIsDisplayed()
        row(foreign.id).assertIsDisplayed()
        row(unrelated.id).assertDoesNotExist()
        screen.runOnIdle {
            assertEquals(listOf(setOf(older.id)), deleted)
            assertTrue(unrelated in history)
        }
    }
}
