package io.hydrion.hub.ui.messages

import android.content.ActivityNotFoundException
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role as UiRole
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.hydrion.hub.R
import io.hydrion.hub.model.*

private fun shareableText(messages: List<Message>, user: String, assistant: String): String? {
    // ACTION_SEND transports the text in one Binder transaction.
    val limit = 64 * 1024
    val text = StringBuilder()
    for (message in messages) {
        if (message.text.isBlank()) continue
        val name = if (message.role == Role.User) user else message.model.ifBlank { assistant }
        if (text.length.toLong() + name.length + message.text.length + 3 > limit) return null
        if (text.isNotEmpty()) text.append("\n\n")
        text.append(name).append('\n').append(message.text)
    }
    return text.toString().takeIf { it.toByteArray().size <= limit }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ChatMessageSelection(chat: Conversation, anchor: Message, close: () -> Unit,
    delete: (Set<String>, Boolean) -> Unit) {
    var keys by rememberSaveable(chat.id, anchor.id) {
        val prompt = if (anchor.role == Role.User) anchor.id else anchor.replyTo
        mutableStateOf(ArrayList(chat.messages.filter {
            it.id == anchor.id || it.id == prompt || (prompt != null && it.replyTo == prompt)
        }.map { it.id }))
    }
    val ids = remember(keys) { keys.toSet() }
    val selected = remember(chat.messages, ids) { chat.messages.filter { it.id in ids } }
    var deleteAll by rememberSaveable { mutableStateOf<Boolean?>(null) }
    val user = stringResource(R.string.drawer_you)
    val assistant = stringResource(R.string.turn_assistant)
    val plainText = remember(selected, user, assistant) {
        shareableText(selected, user, assistant)
    }
    val context = LocalContext.current
    val canDelete = chat.canDeleteSelection(ids, allVersions = false)
    val canDeleteAll = chat.canDeleteSelection(ids, allVersions = true)
    val multipleVersions = ids.any { it in chat.versions }
    val list = rememberLazyListState(chat.messages.indexOfFirst { it.id == anchor.id }.coerceAtLeast(0))
    ModalBottomSheet(onDismissRequest = close, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(.86f).padding(horizontal = 16.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.turn_selection), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                val all = ids.size == chat.messages.size
                TextButton(onClick = { keys = if (all) arrayListOf() else ArrayList(chat.messages.map { it.id }) }) {
                    Text(stringResource(if (all) R.string.turn_select_none else R.string.turn_select_all))
                }
            }
            LazyColumn(Modifier.weight(1f), state = list) {
                items(chat.messages, key = { it.id }) { m ->
                    val checked = m.id in ids
                    Row(Modifier.fillMaxWidth().toggleable(checked, role = UiRole.Checkbox) { on ->
                        keys = ArrayList(if (on) ids + m.id else ids - m.id)
                    }.heightIn(min = 48.dp).padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked, onCheckedChange = null)
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(if (m.role == Role.User) user else m.model.ifBlank { assistant },
                                style = MaterialTheme.typography.labelSmall)
                            val preview = when {
                                m.text.isNotBlank() -> m.text
                                m.error != null -> m.error
                                m.role == Role.User && m.attachments.isNotEmpty() -> m.attachments.first().name
                                m.imageKeys.isNotEmpty() -> stringResource(R.string.turn_media)
                                else -> stringResource(R.string.turn_no_text)
                            }
                            Text(preview, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
            if (ids.isNotEmpty() && !canDelete) Text(stringResource(R.string.turn_selection_dependency),
                style = MaterialTheme.typography.bodySmall)
            if (plainText == null) Text(stringResource(R.string.turn_share_limit), style = MaterialTheme.typography.bodySmall)
            TextButton(enabled = !plainText.isNullOrBlank(), onClick = {
                val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, plainText)
                try { context.startActivity(Intent.createChooser(send, null)) }
                catch (_: ActivityNotFoundException) { Toast.makeText(context, R.string.turn_share_missing, Toast.LENGTH_SHORT).show() }
            }) { Text(stringResource(R.string.turn_share)) }
            TextButton(enabled = canDelete, onClick = { deleteAll = false }) {
                Text(stringResource(R.string.turn_delete_selected, ids.size))
            }
            if (multipleVersions) TextButton(enabled = canDeleteAll, onClick = { deleteAll = true }) {
                Text(stringResource(R.string.turn_delete_selected_all, ids.size))
            }
        }
    }
    deleteAll?.let { all ->
        AlertDialog(onDismissRequest = { deleteAll = null },
            text = { Text(stringResource(if (all) R.string.turn_confirm_selection_all else R.string.turn_confirm_selection, ids.size)) },
            confirmButton = { TextButton(enabled = if (all) canDeleteAll else canDelete, onClick = {
                delete(ids, all); close()
            }) { Text(stringResource(R.string.drawer_delete)) } },
            dismissButton = { TextButton(onClick = { deleteAll = null }) { Text(stringResource(R.string.settings_cancel)) } })
    }
}
