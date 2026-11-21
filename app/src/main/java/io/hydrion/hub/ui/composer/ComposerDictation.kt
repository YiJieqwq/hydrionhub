package io.hydrion.hub.ui.composer

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.speech.RecognizerIntent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import io.hydrion.hub.R
import java.security.MessageDigest

internal class DictationTarget private constructor(
    private val conversationId: String,
    private val draftDigest: ByteArray,
    private val selection: TextRange,
    private val restored: Boolean,
) {
    fun insert(id: String, current: TextFieldValue, speech: String): TextFieldValue? {
        if (id != conversationId || speech.isBlank() || !draftDigest.contentEquals(digest(current.text))) return null
        // The recreated BasicTextField starts with a collapsed cursor at zero.
        if (current.selection != selection && !(restored && current.selection == TextRange.Zero)) return null
        val start = selection.min
        val text = current.text.replaceRange(start, selection.max, speech)
        return TextFieldValue(text, TextRange(start + speech.length))
    }

    companion object {
        private fun digest(text: String) = MessageDigest.getInstance("SHA-256").digest(text.toByteArray())
        fun capture(id: String, draft: TextFieldValue) = DictationTarget(id, digest(draft.text), draft.selection, false)

        // Keep the insertion point across recreation without duplicating an unbounded draft in saved state.
        val Saver = listSaver<DictationTarget?, Any>(
            save = { target ->
                target?.let { listOf(it.conversationId, it.draftDigest, it.selection.start, it.selection.end) }.orEmpty()
            },
            restore = { saved ->
                DictationTarget(saved[0] as String, saved[1] as ByteArray, TextRange(saved[2] as Int, saved[3] as Int), true)
            },
        )
    }
}

@Composable
internal fun rememberChatDictation(
    conversationId: String,
    enabled: Boolean,
    readDraft: () -> TextFieldValue?,
    onDraft: (TextFieldValue) -> Unit,
): () -> Unit {
    if (!enabled) return {}
    // Leaving this editor unregisters its launcher. A later visit gets its own result key.
    return key(conversationId) {
        var pending by rememberSaveable(stateSaver = DictationTarget.Saver) { mutableStateOf<DictationTarget?>(null) }
        val context = LocalContext.current
        fun notice(message: Int) = Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val target = pending ?: return@rememberLauncherForActivityResult
            pending = null
            val draft = readDraft() ?: return@rememberLauncherForActivityResult
            if (result.resultCode == Activity.RESULT_CANCELED) return@rememberLauncherForActivityResult
            val speech = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull { it.isNotBlank() }
            if (result.resultCode != Activity.RESULT_OK || speech == null) {
                notice(R.string.chat_dictation_no_result)
            } else {
                val updated = target.insert(conversationId, draft, speech)
                if (updated != null) onDraft(updated) else notice(R.string.chat_dictation_draft_changed)
            }
        }
        val start: () -> Unit = start@{
            if (pending != null) return@start
            val draft = readDraft() ?: return@start
            pending = DictationTarget.capture(conversationId, draft)
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            try {
                launcher.launch(intent)
            } catch (_: ActivityNotFoundException) {
                pending = null
                notice(R.string.chat_dictation_unavailable)
            } catch (_: SecurityException) {
                pending = null
                notice(R.string.chat_dictation_unavailable)
            }
        }
        start
    }
}
