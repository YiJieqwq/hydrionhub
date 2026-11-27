package io.hydrion.hub.ui.messages

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.CallSplit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.hydrion.hub.R
import io.hydrion.hub.model.*
import io.hydrion.hub.provider.ModelCatalog
import io.hydrion.hub.provider.Provider
import io.hydrion.hub.provider.ProviderModel
import io.hydrion.hub.provider.chatProtocol
import io.hydrion.hub.ui.continueEditedReply
import io.hydrion.hub.ui.sendEditedBranch
import io.hydrion.hub.ui.theme.HubShapes
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.launch

internal enum class TurnSheet { More, Edit, Retry, SelectText, WebPreview, Translate, Selection, Delete, DeleteOthers, DeleteAll }

@Composable
private fun TurnIcon(@DrawableRes icon: Int, label: Int, enabled: Boolean = true, action: () -> Unit) {
    val ink = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) .9f else .35f)
    IconButton(onClick = action, enabled = enabled, modifier = Modifier.size(28.dp)) {
        Icon(painterResource(icon), stringResource(label), Modifier.size(16.dp), tint = ink)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TurnActionSheet(close: () -> Unit, containerColor: Color = MaterialTheme.colorScheme.surface,
    content: @Composable ColumnScope.() -> Unit) {
    ModalBottomSheet(onDismissRequest = close, modifier = Modifier.testTag("turn-action-sheet"),
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = HubShapes.Sheet,
        containerColor = containerColor,
        dragHandle = {
            Box(Modifier.padding(vertical = 6.dp).size(40.dp, 4.dp)
                .background(MaterialTheme.colorScheme.onSurface.copy(alpha = .20f), RoundedCornerShape(2.dp)))
        }, content = content)
}

@Composable
private fun SheetGroup(content: @Composable ColumnScope.() -> Unit) {
    Surface(shape = HubShapes.Small, color = Color.Transparent) {
        Column(content = content)
    }
}

@Composable
private fun SheetDivider() {
    HorizontalDivider(Modifier.padding(start = 44.dp, end = 12.dp), thickness = .5.dp,
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = if (LocalMessageStyle.current.tone == MessageTone.Dark) .08f else .06f))
}

@Composable
private fun SheetAction(id: Int, @DrawableRes icon: Int, enabled: Boolean = true, destructive: Boolean = false, action: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = action)
        .heightIn(min = 44.dp).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        val color = (if (destructive) colors.error else colors.onSurface).copy(alpha = if (enabled) 1f else .38f)
        Icon(painterResource(icon), null, Modifier.size(20.dp), tint = color)
        Text(stringResource(id), color = color, fontSize = 15.sp, lineHeight = 18.75.sp,
            letterSpacing = 0.sp, fontWeight = FontWeight.Normal)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ChatTurnActions(msg: Message, chat: Conversation, vm: HubViewModel, busy: Boolean,
    open: (Message, TurnSheet) -> Unit) {
    if (msg.status.isActive) return
    val user = msg.role == Role.User
    val copyText = rememberPlainTextCopier(R.string.turn_text_copied)
    FlowRow(Modifier.fillMaxWidth(),
        horizontalArrangement = if (user) Arrangement.End else Arrangement.SpaceBetween,
        verticalArrangement = Arrangement.spacedBy(4.dp)) {
        FlowRow(Modifier.align(Alignment.CenterVertically).padding(end = if (user) 0.dp else 12.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp, if (user) Alignment.End else Alignment.Start),
            verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (msg.text.isNotBlank()) {
                TurnIcon(R.drawable.lucide_copy, if (user) R.string.turn_copy else R.string.turn_copy_reply) {
                    copyText(msg.text)
                }
            }
            TurnIcon(R.drawable.lucide_refresh_cw, if (user) R.string.turn_resend else R.string.turn_retry,
                !busy && chat.canRetryChatMessage(msg)) { open(msg, TurnSheet.Retry) }
            if (user) {
                TurnIcon(R.drawable.lucide_pencil, R.string.turn_edit, !busy) { open(msg, TurnSheet.Edit) }
            } else if (msg.text.isNotBlank()) {
                TurnIcon(R.drawable.lucide_languages, R.string.turn_translate) { open(msg, TurnSheet.Translate) }
            }
            TurnIcon(R.drawable.lucide_ellipsis, if (user) R.string.turn_more else R.string.turn_more_reply) {
                open(msg, TurnSheet.More)
            }
            chat.versions[msg.id]?.let { group ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TurnIcon(R.drawable.lucide_chevron_left, R.string.turn_previous, !busy && group.selected > 0) {
                        vm.chooseMessageVersion(msg.id, group.selected - 1)
                    }
                    Text("${group.selected + 1}/${group.others.size + 1}", Modifier.widthIn(min = 28.dp),
                        textAlign = TextAlign.Center, fontSize = 12.sp, letterSpacing = 0.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = .8f))
                    TurnIcon(R.drawable.lucide_chevron_right, R.string.turn_next, !busy && group.selected < group.others.size) {
                        vm.chooseMessageVersion(msg.id, group.selected + 1)
                    }
                }
            }
        }
        if (!user) ReplyUsageButton(msg, Modifier.align(Alignment.CenterVertically))
    }
}

private val Pair<ProviderModel, Provider>.routeLabel: String
    get() = "${second.name} · ${first.name}"

private fun Conversation.retryModelLabel(target: Message, catalog: ModelCatalog): String? {
    val user = if (target.role == Role.User) target else messages.find { it.id == target.replyTo }
    val reply = if (target.role == Role.Assistant) target else messages.find { it.replyTo == target.id }
    val image = reply?.imageInvocation ?: user?.imageInvocation
    val imageRetry = reply?.continuesAssistant != true && user?.retriesImage(mode, reply) == true
    if (imageRetry && image?.remoteModel == null) return catalog.selectedEntry(Mode.Image)?.routeLabel
    val route = when {
        imageRetry -> image?.remoteModel
        reply?.continuesAssistant == true -> reply.remoteModel
        else -> reply?.remoteModel ?: user?.remoteModel
    }
    if (route == null) return (reply?.model ?: user?.model)?.takeIf(String::isNotBlank)
    val provider = catalog.providers.find { it.id == route.providerId }
    val model = provider?.models?.find { it.id == route.modelId }
    return "${provider?.name ?: route.providerId} · ${model?.name ?: route.modelId}"
}

@Composable
private fun AssistantReplyEditor(msg: Message, chat: Conversation, vm: HubViewModel, busy: Boolean, close: () -> Unit) {
    var text by rememberSaveable(chat.id, msg.id) { mutableStateOf(msg.text) }
    var submitting by remember { mutableStateOf(false) }
    var issue by remember(text) { mutableStateOf<String?>(null) }
    val catalog by vm.providers.catalog.collectAsState()
    val scope = rememberCoroutineScope()
    val valid = !busy && !submitting && text.isNotBlank()
    TurnActionSheet(close) {
        Column(Modifier.fillMaxWidth().heightIn(max = (LocalConfiguration.current.screenHeightDp * .8f).dp)
            .padding(start = 16.dp, end = 16.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(enabled = valid && chat.canContinueAfter(msg), onClick = {
                    val before = vm.state.value
                    if (before.current != chat || submitting) return@TextButton
                    val submittedText = text
                    val selectedCatalog = catalog
                    submitting = true
                    scope.launch {
                        try {
                            val problem = continueEditedReply(vm, before, selectedCatalog, msg, submittedText)
                            if (problem == null) close() else issue = problem
                        } finally {
                            submitting = false
                        }
                    }
                }) { Text(stringResource(R.string.turn_save_continue)) }
                Text(stringResource(R.string.turn_edit), Modifier.weight(1f), textAlign = TextAlign.Center,
                    fontSize = 18.sp, fontWeight = FontWeight.Medium)
                TextButton(enabled = valid, onClick = {
                    if (vm.state.value.current == chat && vm.editMessage(msg.id, text)) close()
                }) { Text(stringResource(R.string.settings_save)) }
            }
            issue?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth(), enabled = !submitting, minLines = 4, maxLines = 16)
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
    val copySource = rememberPlainTextCopier(R.string.turn_markdown_copied)
    val close = { show(null) }
    val ordinal = group?.selected ?: 0
    val openedOrdinal = rememberSaveable(chat.id, msg.id, sheet) { ordinal }
    if ((sheet == TurnSheet.Edit || sheet == TurnSheet.Retry || sheet == TurnSheet.More) && openedOrdinal != ordinal) {
        LaunchedEffect(Unit) { close() }
        return
    }
    when (sheet) {
        TurnSheet.More -> TurnActionSheet(close, containerColor = if (LocalMessageStyle.current.tone == MessageTone.Dark)
            MaterialTheme.colorScheme.background else MaterialTheme.colorScheme.surface) {
            Column(Modifier.heightIn(max = (LocalConfiguration.current.screenHeightDp.dp * .8f - 16.dp).coerceAtLeast(1.dp))
                .verticalScroll(rememberScrollState()).padding(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (msg.text.isNotBlank()) SheetGroup {
                    SheetAction(R.string.turn_select_copy, R.drawable.lucide_text_cursor_input) { show(TurnSheet.SelectText) }
                    SheetDivider()
                    SheetAction(R.string.turn_copy_markdown_source, R.drawable.lucide_code) { copySource(msg.text); close() }
                    SheetDivider()
                    SheetAction(R.string.turn_web_preview, R.drawable.lucide_book_open_text) { show(TurnSheet.WebPreview) }
                }
                SheetGroup {
                    if (!user) {
                        SheetAction(R.string.turn_edit, R.drawable.lucide_pencil, !busy) { show(TurnSheet.Edit) }
                        SheetDivider()
                    }
                    if (!user && msg.imageKeys.isNotEmpty()) {
                        SheetAction(R.string.turn_prompt, R.drawable.lucide_image, !busy) { vm.preparePrompt(msg.id); close() }
                        SheetDivider()
                    }
                    SheetAction(R.string.turn_selection, R.drawable.lucide_square_check, !busy) { show(TurnSheet.Selection) }
                }
                SheetGroup {
                    SheetAction(if (fixed) R.string.turn_clear_start else R.string.turn_set_start, R.drawable.lucide_flag, !busy) {
                        vm.setContextStart(if (fixed) null else msg.id); close()
                    }
                    SheetDivider()
                    SheetAction(R.string.turn_fork, R.drawable.lucide_git_fork, !busy) {
                        if (vm.state.value.current == chat) vm.forkMessage(msg.id)
                        close()
                    }
                }
                SheetGroup {
                    val deleteLabel = when {
                        group != null -> R.string.turn_delete_version
                        user -> R.string.turn_delete
                        else -> R.string.turn_delete_this_reply
                    }
                    SheetAction(deleteLabel, R.drawable.lucide_trash2, !busy && (group != null || !protected), destructive = true) {
                        show(TurnSheet.Delete)
                    }
                    if (group != null) {
                        SheetDivider()
                        SheetAction(R.string.turn_delete_others, R.drawable.lucide_list_minus, !busy, destructive = true) {
                            show(TurnSheet.DeleteOthers)
                        }
                        SheetDivider()
                        SheetAction(R.string.turn_delete_all, R.drawable.lucide_trash2, !busy && !protected, destructive = true) {
                            show(TurnSheet.DeleteAll)
                        }
                    }
                }
            }
        }
        TurnSheet.WebPreview -> ReplyWebPreview(msg.text, close)
        TurnSheet.Translate -> TranslationDialog(chat.id, msg, vm, close)
        TurnSheet.Selection -> ChatMessageSelection(chat, msg, close) { ids, all ->
            vm.deleteSelectedVersions(chat.id, ids, all)
        }
        TurnSheet.SelectText -> TurnActionSheet(close) {
            val copyAll = rememberPlainTextCopier(R.string.turn_text_copied)
            Column(Modifier.fillMaxWidth().height((LocalConfiguration.current.screenHeightDp.dp * .8f - 16.dp).coerceAtLeast(1.dp))
                .padding(horizontal = 16.dp, vertical = 8.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween) {
                    IconButton(onClick = { copySource(msg.text) }) {
                        Icon(painterResource(R.drawable.lucide_code), stringResource(R.string.turn_copy_markdown_source), Modifier.size(20.dp))
                    }
                    Text(stringResource(R.string.turn_select_copy), fontSize = 18.sp, fontWeight = FontWeight.Medium)
                    TextButton(onClick = { copyAll(msg.text) }) { Text(stringResource(R.string.turn_copy_all)) }
                }
                Box(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(top = 8.dp)) {
                    SelectionContainer { Text(msg.text, fontSize = 15.sp, lineHeight = 22.5.sp, letterSpacing = 0.sp) }
                }
                TextButton(onClick = close, modifier = Modifier.align(Alignment.End)) { Text(stringResource(android.R.string.ok)) }
            }
        }
        TurnSheet.Edit -> if (!user) AssistantReplyEditor(msg, chat, vm, busy, close) else {
            var input by rememberSaveable(chat.id, msg.id) { mutableStateOf(msg.text) }
            val before = vm.state.collectAsState().value
            val catalog = vm.providers.catalog.collectAsState().value
            val canResend = retryAllowed
            val original = if (canResend) chat.messages.find { it.replyTo == msg.id } else null
            val retryUnavailable = canResend && chat.mode == Mode.Chat && catalog.retryChatRouteIssue(msg, original) != null
            val scope = key(chat.id, msg.id, sheet) { rememberCoroutineScope() }
            var preparing by remember(chat.id, msg.id, sheet) { mutableStateOf(false) }
            var issue by remember(chat.id, msg.id, sheet, input) { mutableStateOf<String?>(null) }
            val dismiss = { scope.coroutineContext.cancelChildren(); close() }
            val valid = !busy && !preparing && (input.isNotBlank() || msg.attachments.isNotEmpty())
            AlertDialog(onDismissRequest = dismiss, title = { Text(stringResource(R.string.turn_edit)) },
                text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = input, onValueChange = { input = it }, enabled = !preparing, minLines = 2, maxLines = 8)
                    if (chat.mode == Mode.Chat && chat.messages.lastOrNull()?.id != msg.id) {
                        val chatModel = catalog.selectedEntry(Mode.Chat)
                        val editedDraft = before.draft.copy(text = input, attachments = msg.attachments)
                        val usesImage = chatModel != null && chatModel.second.chatProtocol() == null &&
                            selectedTool(Mode.Chat, editedDraft, vm.webSearch.settings.value.localEnabled)?.startsWith("图片生成") == true
                        val imageModel = if (usesImage) catalog.selectedEntry(Mode.Image) else null
                        if (chatModel != null) Text(stringResource(R.string.turn_branch_model, chatModel.routeLabel))
                        if (imageModel != null) Text(stringResource(R.string.turn_branch_image_model, imageModel.routeLabel))
                        val problem = when {
                            chatModel == null -> stringResource(R.string.model_settings_unavailable)
                            usesImage && imageModel == null -> stringResource(R.string.turn_branch_image_unavailable)
                            else -> issue
                        }
                        if (problem != null) Text(problem, color = MaterialTheme.colorScheme.error)
                        val changed = stringResource(R.string.turn_branch_model_changed)
                        TextButton(enabled = valid && chatModel != null && (!usesImage || imageModel != null), onClick = {
                            if (before.current.id != chat.id) {
                                issue = changed
                            } else if (!preparing) {
                                val text = input
                                issue = null
                                preparing = true
                                scope.launch {
                                    try {
                                        val error = sendEditedBranch(vm, before, catalog, msg.id, text)
                                        if (error == null) close() else issue = error
                                    } finally {
                                        preparing = false
                                    }
                                }
                            }
                        }) {
                            Icon(Icons.AutoMirrored.Outlined.CallSplit, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.turn_send_in_branch))
                        }
                    }
                    if (canResend) {
                        chat.retryModelLabel(msg, catalog)?.let { label ->
                            Text(stringResource(R.string.turn_save_send_model, label), style = MaterialTheme.typography.labelMedium)
                        }
                        if (retryUnavailable) Text(stringResource(R.string.turn_resend_unavailable), color = MaterialTheme.colorScheme.error)
                    }
                } }, confirmButton = { Column(horizontalAlignment = Alignment.End) {
                    TextButton(enabled = valid, onClick = {
                        if (vm.state.value.current == chat && vm.editMessage(msg.id, input)) close()
                    }) { Text(stringResource(R.string.settings_save)) }
                    if (canResend) TextButton(enabled = valid && !retryUnavailable, onClick = {
                        val liveCatalog = vm.providers.catalog.value
                        if (vm.state.value.current == chat &&
                            (chat.mode != Mode.Chat || liveCatalog.retryChatRouteIssue(msg, original) == null) &&
                            vm.editMessage(msg.id, input)) {
                            vm.regenerate(msg.id)
                            close()
                        }
                    }) { Text(stringResource(R.string.turn_save_send)) }
                } }, dismissButton = { TextButton(onClick = dismiss) { Text(stringResource(R.string.settings_cancel)) } })
        }
        TurnSheet.Retry, TurnSheet.Delete, TurnSheet.DeleteOthers, TurnSheet.DeleteAll -> {
            val action = sheet
            val retry = action == TurnSheet.Retry
            val message = when {
                retry -> {
                    val catalog = vm.providers.catalog.collectAsState().value
                    val explanation = stringResource(R.string.turn_retry_explanation)
                    chat.retryModelLabel(msg, catalog)?.let { label ->
                        explanation + "\n\n" + stringResource(R.string.turn_retry_model, label)
                    } ?: explanation
                }
                action == TurnSheet.DeleteOthers -> {
                    if (group == null) {
                        LaunchedEffect(msg.id) { close() }
                        return
                    }
                    val count = group.others.size
                    pluralStringResource(R.plurals.turn_confirm_others, count, count)
                }
                action == TurnSheet.Delete && group != null -> stringResource(R.string.turn_confirm_one)
                user -> stringResource(R.string.turn_delete_pair)
                group != null -> stringResource(R.string.turn_delete_reply_all)
                else -> stringResource(R.string.turn_delete_reply)
            }
            AlertDialog(onDismissRequest = close, text = { Text(message) }, confirmButton = {
                TextButton(enabled = !busy, onClick = {
                    when (action) {
                        TurnSheet.Retry -> if (vm.state.value.current == chat && retryAllowed) vm.regenerate(msg.id)
                        TurnSheet.Delete -> vm.deleteChatVersion(msg.id)
                        TurnSheet.DeleteOthers -> vm.deleteChatVersion(msg.id, otherVersions = true, expectedGroup = group)
                        TurnSheet.DeleteAll -> vm.deleteChatMessage(msg.id)
                        else -> Unit
                    }
                    close()
                }) { Text(stringResource(if (retry) (if (user) R.string.turn_resend else R.string.turn_retry) else R.string.drawer_delete)) }
            }, dismissButton = { TextButton(onClick = close) { Text(stringResource(R.string.settings_cancel)) } })
        }
    }
}
