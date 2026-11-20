package io.hydrion.hub.ui.composer

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import org.junit.Assert.*
import org.junit.Test

class ComposerQueryEditTest {
    @Test fun explicitReturnSubmitsAtTheCaretOrAcrossASelection() {
        val before = TextFieldValue("a模型b", TextRange(3, 1))
        for (newline in listOf("\r", "\n", "\r\n")) {
            assertTrue(isQuerySubmitEdit(before, TextFieldValue("a${newline}b")))
            assertTrue(isQuerySubmitEdit(TextFieldValue(), TextFieldValue(newline)))
        }
        assertFalse(isQuerySubmitEdit(before, TextFieldValue("x\ny")))
        assertFalse(isQuerySubmitEdit(before, TextFieldValue("ab")))
    }

    @Test fun pastedMultilineSearchStaysInTheQueryAndPreservesSelection() {
        val old = TextFieldValue("x", TextRange(1))
        val pasted = TextFieldValue("xA\r\nB", TextRange(5), TextRange(2, 4))
        assertFalse(isQuerySubmitEdit(old, pasted))
        assertEquals(TextFieldValue("xA  B", TextRange(5)), pasted.singleLineQuery())
        assertSame(old, old.singleLineQuery())
    }
}
