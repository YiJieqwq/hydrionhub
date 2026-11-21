package io.hydrion.hub.ui.messages

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.CallSplit
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import io.hydrion.hub.R
import io.hydrion.hub.model.*

internal enum class TurnSheet { More, Edit, Retry, Fork, SelectText, Usage, Selection, Delete, DeleteOthers, DeleteAll }

@Composable
private fun TurnIcon(icon: ImageVector, label: Int, enabled: Boolean = true, action: () -> Unit) {
    IconButton(onClick = action, enabled = enabled) {
        Icon(icon, stringResource(label), Modifier.size(16.dp))
    }
}

@Composable
internal fun ChatActionRow(user: Boolean, hasText: Boolean, busy: Boolean, retryAllowed: Boolean,
    group: MessageVersions?, copy: () -> Unit, retry: () -> Unit, edit: () -> Unit,
    more: () -> Unit, version: (Int) -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
        if (hasText) TurnIcon(Icons.Outlined.ContentCopy, if (user) R.string.turn_copy else R.string.turn_copy_reply, action = copy)
        TurnIcon(Icons.Outlined.Refresh, if (user) R.string.turn_resend else R.string.turn_retry, !busy && retryAllowed, retry)
        if (user) TurnIcon(Icons.Outlined.Edit, R.string.turn_edit, !busy, edit)
        TurnIcon(Icons.Outlined.MoreHoriz, if (user) R.string.turn_more else R.string.turn_more_reply, action = more)
        if (group != null) {
            TurnIcon(Icons.AutoMirrored.Outlined.ArrowBack, R.string.turn_previous, !busy && group.selected > 0) { version(group.selected - 1) }
            Text("${group.selected + 1}/${group.others.size + 1}", style = MaterialTheme.typography.labelSmall)
            TurnIcon(Icons.AutoMirrored.Outlined.ArrowForward, R.string.turn_next, !busy && group.selected < group.others.size) { version(group.selected + 1) }
        }
    }
}

@Composable
private fun SheetAction(id: Int, icon: ImageVector, enabled: Boolean = true, action: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = action)
        .heightIn(min = 48.dp).padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        val color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 1f else .38f)
        Icon(icon, null, Modifier.size(19.dp), tint = color)
        Text(stringResource(id), color = color)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ChatTurnActions(msg: Message, chat: Conversation, vm: HubViewModel, busy: Boolean,
    open: (Message, TurnSheet) -> Unit, bubble: @Composable ((() -> Unit) -> Unit)? = null) {
    val clipboard = LocalClipboardManager.current
    Column(horizontalAlignment = if (msg.role == Role.User) Alignment.End else Alignment.Start) {
        if (bubble != null) {
            bubble { open(msg, TurnSheet.More) }
            Spacer(Modifier.height(8.dp))
        }
        if (!msg.status.isActive) {
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = if (msg.role == Role.User) Arrangement.End else Arrangement.Start) {
                ChatActionRow(msg.role == Role.User, msg.text.isNotBlank(), busy, chat.canRetryChatMessage(msg),
                    chat.versions[msg.id], { clipboard.setText(AnnotatedString(msg.text)) }, { open(msg, TurnSheet.Retry) },
                    { open(msg, TurnSheet.Edit) }, { open(msg, TurnSheet.More) }, { vm.chooseMessageVersion(msg.id, it) })
                if (msg.role == Role.Assistant) msg.reportedUsage?.contextTokens?.let { total ->
                    TextButton(onClick = { open(msg, TurnSheet.Usage) }) {
                        Text(stringResource(R.string.turn_tokens, total), style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }
}

/** Owned by the timeline, not a lazy item that can be disposed when the edit keyboard resizes it. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ChatTurnOverlay(msg: Message, chat: Conversation, vm: HubViewModel, busy: Boolean,
    sheet: TurnSheet, show: (TurnSheet?) -> Unit) {
    val user = msg.role == Role.User
    val group = chat.versions[msg.id]
    val retryAllowed = chat.canRetryChatMessage(msg)
    val fixed = chat.contextStart == msg.id
    val protected = fixed || (user && chat.contextStart != null && chat.messages.any { it.replyTo == msg.id && it.id == chat.contextStart })
    val clipboard = LocalClipboardManager.current
    val fallbackTitle = stringResource(R.string.turn_fork_title, chat.title)
    var input by rememberSaveable(chat.id, msg.id, sheet) {
        mutableStateOf(if (sheet == TurnSheet.Fork) fallbackTitle else msg.text)
    }
    val close = { show(null) }
    when (sheet) {
        TurnSheet.More -> ModalBottomSheet(onDismissRequest = close,
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 12.dp)) {
                if (msg.text.isNotBlank()) {
                    SheetAction(if (user) R.string.turn_copy else R.string.turn_copy_reply, Icons.Outlined.ContentCopy) { clipboard.setText(AnnotatedString(msg.text)); close() }
                    SheetAction(R.string.turn_select_copy, Icons.Outlined.TextFields) { show(TurnSheet.SelectText) }
                }
                SheetAction(R.string.turn_edit, Icons.Outlined.Edit, !busy) { show(TurnSheet.Edit) }
                if (!user && msg.imageKeys.isNotEmpty()) SheetAction(R.string.turn_prompt, Icons.Outlined.Image, !busy) {
                    vm.preparePrompt(msg.id); close()
                }
                SheetAction(R.string.turn_selection, Icons.Outlined.Checklist, !busy) { show(TurnSheet.Selection) }
                HorizontalDivider()
                SheetAction(if (fixed) R.string.turn_clear_start else R.string.turn_set_start, Icons.Outlined.Flag, !busy) {
                    vm.setContextStart(if (fixed) null else msg.id); close()
                }
                SheetAction(R.string.turn_fork, Icons.AutoMirrored.Outlined.CallSplit, !busy) {
                    show(TurnSheet.Fork)
                }
                HorizontalDivider()
                SheetAction(if (group == null) R.string.turn_delete else R.string.turn_delete_version,
                    Icons.Outlined.Delete, !busy && (group != null || !protected)) { show(TurnSheet.Delete) }
                if (group != null) {
                    SheetAction(R.string.turn_delete_others, Icons.Outlined.Delete, !busy) { show(TurnSheet.DeleteOthers) }
                    SheetAction(R.string.turn_delete_all, Icons.Outlined.Delete, !busy && !protected) { show(TurnSheet.DeleteAll) }
                }
            }
        }
        TurnSheet.Usage -> AlertDialog(onDismissRequest = close, title = { Text(stringResource(R.string.turn_usage)) },
            text = { Column {
                val usage = msg.reportedUsage
                usage?.contextTokens?.let { Text(stringResource(R.string.turn_tokens, it)) }
                usage?.inputTokens?.let { Text(stringResource(R.string.turn_input_tokens, it)) }
                usage?.outputTokens?.let { Text(stringResource(R.string.turn_output_tokens, it)) }
            } }, confirmButton = { TextButton(onClick = close) { Text(stringResource(android.R.string.ok)) } })
        TurnSheet.Selection -> ChatMessageSelection(chat, msg, close) { ids, all ->
            vm.deleteSelectedVersions(chat.id, ids, all)
        }
        TurnSheet.SelectText -> AlertDialog(onDismissRequest = close, title = { Text(stringResource(R.string.turn_select_copy)) },
            text = { SelectionContainer { Text(msg.text, Modifier.verticalScroll(rememberScrollState())) } },
            confirmButton = { TextButton(onClick = close) { Text(stringResource(android.R.string.ok)) } })
        TurnSheet.Fork, TurnSheet.Edit -> {
            val fork = sheet == TurnSheet.Fork
            val valid = !busy && (input.isNotBlank() || (!fork && user && msg.attachments.isNotEmpty()))
            AlertDialog(onDismissRequest = close, title = { Text(stringResource(if (fork) R.string.turn_fork else R.string.turn_edit)) },
                text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (fork) Text(stringResource(R.string.turn_fork_hint))
                    OutlinedTextField(value = input, onValueChange = { input = it }, singleLine = fork,
                        minLines = if (fork) 1 else 2, maxLines = if (fork) 1 else 8)
                } }, confirmButton = { Column(horizontalAlignment = Alignment.End) {
                    TextButton(enabled = valid, onClick = {
                        if (fork) vm.forkMessage(msg.id, input.trim()) else vm.editMessage(msg.id, input)
                        close()
                    }) { Text(stringResource(if (fork) R.string.turn_fork else R.string.settings_save)) }
                    if (user && !fork && retryAllowed) TextButton(enabled = valid, onClick = {
                        if (vm.editMessage(msg.id, input)) vm.regenerate(msg.id)
                        close()
                    }) { Text(stringResource(R.string.turn_save_send)) }
                } }, dismissButton = { TextButton(onClick = close) { Text(stringResource(R.string.settings_cancel)) } })
        }
        TurnSheet.Retry, TurnSheet.Delete, TurnSheet.DeleteOthers, TurnSheet.DeleteAll -> {
            val action = sheet
            val retry = action == TurnSheet.Retry
            val message = when {
                retry -> R.string.turn_retry_explanation
                action == TurnSheet.DeleteOthers -> R.string.turn_confirm_others
                action == TurnSheet.Delete && group != null -> R.string.turn_confirm_one
                user -> R.string.turn_delete_pair
                group != null -> R.string.turn_delete_reply_all
                else -> R.string.turn_delete_reply
            }
            AlertDialog(onDismissRequest = close, text = { Text(stringResource(message)) }, confirmButton = {
                TextButton(enabled = !busy, onClick = {
                    when (action) {
                        TurnSheet.Retry -> vm.regenerate(msg.id)
                        TurnSheet.Delete -> vm.deleteChatVersion(msg.id)
                        TurnSheet.DeleteOthers -> vm.deleteChatVersion(msg.id, otherVersions = true)
                        TurnSheet.DeleteAll -> vm.deleteChatMessage(msg.id)
                        else -> Unit
                    }
                    close()
                }) { Text(stringResource(if (retry) (if (user) R.string.turn_resend else R.string.turn_retry) else R.string.drawer_delete)) }
            }, dismissButton = { TextButton(onClick = close) { Text(stringResource(R.string.settings_cancel)) } })
        }
    }
}
