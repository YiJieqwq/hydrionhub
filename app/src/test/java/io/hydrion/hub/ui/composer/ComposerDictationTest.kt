package io.hydrion.hub.ui.composer

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ComposerDictationTest {
    private val original = TextFieldValue("前文\n旧文字\n后文", TextRange(6, 3))

    @Test fun saveBeforeFinalWaitsAndReplacesOnlyTheOriginalReversedSelection() {
        val session = DictationSession("chat", original)
        assertTrue(session.choose(DictationAction.Save))
        assertTrue(session.waitingForFinal)
        assertFalse(session.choose(DictationAction.Send))
        assertNull(session.consume("chat", original))

        val speech = "新🎈"
        assertTrue(session.finalResult(speech))
        assertFalse(session.waitingForFinal)
        val expected = TextFieldValue("前文\n新🎈\n后文", TextRange(3 + speech.length))
        assertEquals(DictationCommit(DictationAction.Save, expected), session.consume("chat", original))
        assertFalse(session.isActive)
        assertNull(session.consume("chat", original))
    }

    @Test fun finalBeforeChoiceWaitsWithoutSendingAndOnlyTheFirstChoiceIsUsed() {
        val draft = TextFieldValue("hello world", TextRange(6))
        val session = DictationSession("chat", draft)
        assertTrue(session.finalResult("wide "))
        assertTrue(session.isActive)
        assertTrue(session.hasFinal)
        assertFalse(session.waitingForFinal)
        assertNull(session.consume("chat", draft))
        assertFalse(session.finalResult("duplicate"))

        assertTrue(session.choose(DictationAction.Send))
        assertFalse(session.choose(DictationAction.Save))
        assertEquals(DictationCommit(DictationAction.Send, TextFieldValue("hello wide world", TextRange(11))),
            session.consume("chat", draft))
        assertFalse(session.finalResult("late"))
        assertNull(session.consume("chat", draft))
    }

    @Test fun cancellationDiscardsBothEarlyAndLateResultsEvenAfterStartingAgainInTheSameDraft() {
        val canceledBefore = DictationSession("chat", original)
        canceledBefore.choose(DictationAction.Send)
        canceledBefore.cancel()
        val next = DictationSession("chat", original)
        next.choose(DictationAction.Save)
        assertFalse(canceledBefore.finalResult("late"))
        assertNull(canceledBefore.consume("chat", original))
        assertNull(next.consume("chat", original))
        assertTrue(next.finalResult("new"))
        assertEquals("前文\nnew\n后文", next.consume("chat", original)?.draft?.text)

        val canceledAfter = DictationSession("chat", original)
        canceledAfter.finalResult("not accepted")
        canceledAfter.cancel()
        assertFalse(canceledAfter.choose(DictationAction.Send))
        assertNull(canceledAfter.consume("chat", original))
    }

    @Test fun blankFinalEndsTheRequestRegardlessOfWhetherAChoiceWasMade() {
        for (choice in listOf(null, DictationAction.Save, DictationAction.Send)) {
            val session = DictationSession("chat", original)
            if (choice != null) assertTrue(session.choose(choice))
            assertFalse(session.finalResult(" \n "))
            assertFalse(session.isActive)
            assertFalse(session.finalResult("late"))
            assertFalse(session.choose(DictationAction.Send))
            assertNull(session.consume("chat", original))
        }
    }

    @Test fun rejectsAndConsumesRequestsWhenConversationSelectionTextOrEligibilityChanged() {
        val changed = listOf(
            "other chat" to original,
            "chat" to original.copy(selection = TextRange.Zero),
            "chat" to original.copy(text = "changed draft"),
            "chat" to null,
        )
        for (choice in DictationAction.entries) {
            for ((id, currentDraft) in changed) {
                val session = DictationSession("chat", original)
                session.finalResult("speech")
                session.choose(choice)
                assertNull(session.consume(id, currentDraft))
                assertFalse(session.isActive)
                assertNull(session.consume("chat", original))
            }
        }
    }
}
