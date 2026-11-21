package io.hydrion.hub.ui.composer

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue

internal enum class DictationAction { Save, Send }

internal data class DictationCommit(val action: DictationAction, val draft: TextFieldValue)

/** One recognizer request, owned by the composer on the main thread. */
internal class DictationSession(private val conversationId: String, private val draft: TextFieldValue) {
    private var action: DictationAction? = null
    private var transcript: String? = null

    var isActive = true
        private set
    val hasFinal get() = transcript != null
    val waitingForFinal get() = isActive && action != null && !hasFinal

    fun choose(action: DictationAction): Boolean {
        if (!isActive || this.action != null) return false
        this.action = action
        return true
    }

    fun finalResult(text: String): Boolean {
        if (!isActive || hasFinal) return false
        if (text.isBlank()) {
            cancel()
            return false
        }
        transcript = text
        return true
    }

    /** A null current draft means the editor is no longer eligible, e.g. its attachments changed. */
    fun consume(id: String, draft: TextFieldValue?): DictationCommit? {
        val choice = action ?: return null
        val speech = transcript ?: return null
        cancel()
        if (draft == null || conversationId != id ||
            draft.text != this.draft.text || draft.selection != this.draft.selection
        ) {
            return null
        }

        val selection = draft.selection
        val text = draft.text.replaceRange(selection.min, selection.max, speech)
        return DictationCommit(choice, TextFieldValue(text, TextRange(selection.min + speech.length)))
    }

    fun cancel() {
        isActive = false
        action = null
        transcript = null
    }
}
