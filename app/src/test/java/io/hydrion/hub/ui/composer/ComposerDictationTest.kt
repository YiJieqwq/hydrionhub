package io.hydrion.hub.ui.composer

import androidx.compose.runtime.saveable.SaverScope
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ComposerDictationTest {
    private val old = TextFieldValue("前文\n旧文字\n后文", TextRange(6, 3))
    private val target = DictationTarget.capture("chat", old)

    @Test fun replacesReversedSelectionAndPlacesCursorAfterSpeech() {
        val speech = "新🎈"
        assertEquals(TextFieldValue("前文\n新🎈\n后文", TextRange(3 + speech.length)), target.insert("chat", old, speech))
    }

    @Test fun insertsAtCursorWithoutChangingSurroundingText() {
        val draft = TextFieldValue("hello world", TextRange(6))
        assertEquals(TextFieldValue("hello wide world", TextRange(11)),
            DictationTarget.capture("chat", draft).insert("chat", draft, "wide "))
    }

    @Test fun rejectsDifferentChatDraftOrSelectionAndEmptyResults() {
        assertNull(target.insert("other-chat", old, "语音"))
        assertNull(target.insert("chat", old.copy(text = "替换后的消息", selection = TextRange(6, 3)), "语音"))
        assertNull(target.insert("chat", old.copy(selection = TextRange.Zero), "语音"))
        assertNull(target.insert("chat", old, "  "))
    }

    @Test fun recreationRestoresTheOriginalInsertionPointOnlyForAnUnchangedDraft() {
        val scope = object : SaverScope { override fun canBeSaved(value: Any) = true }
        val saved = with(DictationTarget.Saver) { requireNotNull(scope.save(target)) }
        val restored = requireNotNull(DictationTarget.Saver.restore(saved))
        assertEquals(TextFieldValue("前文\n新\n后文", TextRange(4)),
            restored.insert("chat", TextFieldValue(old.text), "新"))
        assertNull(restored.insert("chat", TextFieldValue("新的草稿"), "新"))
        assertNull(restored.insert("chat", old.copy(selection = TextRange(2)), "新"))
        assertNull(restored.insert("other-chat", TextFieldValue(old.text), "新"))
    }
}
