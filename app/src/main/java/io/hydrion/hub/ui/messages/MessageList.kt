package io.hydrion.hub.ui.messages

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.round
import androidx.compose.ui.unit.dp
import io.hydrion.hub.model.*
import io.hydrion.hub.R
import io.hydrion.hub.provider.DEEPSEEK_PROVIDER_ID
import io.hydrion.hub.ui.profile.LocalProfileStore
import io.hydrion.hub.ui.profile.ProfileAvatar
import io.hydrion.hub.ui.theme.IonMark
import kotlinx.coroutines.flow.collect
import java.text.DateFormat
import java.util.Date

fun matchingMessages(state: HubState, query: String): List<String> {
    val needle = query.trim()
    if (needle.isEmpty()) return emptyList()
    return state.current.messages.filter { message ->
        message.text.contains(needle, ignoreCase = true) ||
            (message.imageKeys.isNotEmpty() && message.prompt.contains(needle, ignoreCase = true)) ||
            message.thought.contains(needle, ignoreCase = true) ||
            message.tool?.contains(needle, ignoreCase = true) == true ||
            message.error?.contains(needle, ignoreCase = true) == true ||
            (message.role == Role.User && message.attachments.any { it.name.contains(needle, ignoreCase = true) })
    }.map { it.id }
}

@Composable
fun MessageList(
    vm: HubViewModel,
    state: HubState,
    searchQuery: String,
    match: String?,
    searchJump: Long,
    modifier: Modifier = Modifier,
    floatingBars: PaddingValues = PaddingValues(0.dp),
    composerBoundsInWindow: () -> IntRect? = { null },
) {
    val messages = state.current.messages
    val needle = searchQuery.trim()
    val list = rememberLazyListState()
    var followBottom by rememberSaveable { mutableStateOf(true) }
    // A drag/fling away from the end takes ownership of the viewport until the user returns.
    LaunchedEffect(list) {
        snapshotFlow { list.isScrollInProgress to list.canScrollForward }.collect { (moving, hasMore) ->
            if (moving) followBottom = !hasMore
        }
    }
    LaunchedEffect(match, needle, searchJump) {
        if (match != null) {
            followBottom = false
            val index = messages.indexOfFirst { it.id == match }
            if (index >= 0) list.requestScrollToItem(index)
        }
    }
    LaunchedEffect(messages.lastOrNull(), messages.size, needle, followBottom) {
        if (!followBottom || needle.isNotEmpty() || messages.isEmpty()) return@LaunchedEffect
        // Resizing the keyboard, attachments or error banner can move the visible bottom without adding a message.
        snapshotFlow { list.layoutInfo.viewportSize.height - list.layoutInfo.afterContentPadding }.collect {
            if (!list.isScrollInProgress) list.requestScrollToItem(messages.size)
        }
    }
    // Read Scaffold's measured bars during LazyColumn measurement, including every IME frame.
    val padding = remember(floatingBars) {
        object : PaddingValues {
            override fun calculateLeftPadding(layoutDirection: LayoutDirection) = 16.dp
            override fun calculateRightPadding(layoutDirection: LayoutDirection) = 16.dp
            override fun calculateTopPadding() = floatingBars.calculateTopPadding() + 12.dp
            override fun calculateBottomPadding() = floatingBars.calculateBottomPadding() + 12.dp
        }
    }
    var actionOwner by rememberSaveable(state.current.id) { mutableStateOf<String?>(null) }
    var actionSheet by rememberSaveable(state.current.id) { mutableStateOf<TurnSheet?>(null) }
    val open: (Message, TurnSheet) -> Unit = { target, sheet -> actionOwner = target.id; actionSheet = sheet }
    MessageCanvas(LocalMessageStyle.current, modifier.fillMaxSize()) {
        val edges = if (state.current.mode == Mode.Chat) {
            val origin = remember { mutableStateOf<IntOffset?>(null) }
            val background = MaterialTheme.colorScheme.background
            val brushes = remember(background) { ChatEdgeBrushes(background) }
            Modifier.onGloballyPositioned { origin.value = it.positionInWindow().round() }
                .chatEdgeFades(floatingBars, brushes, list, origin, composerBoundsInWindow)
        } else Modifier
        LazyColumn(
            state = list, modifier = Modifier.fillMaxSize().testTag("message-timeline").then(edges), contentPadding = padding,
            verticalArrangement = Arrangement.spacedBy(if (state.current.mode == Mode.Chat) 12.dp else 14.dp)
        ) {
            itemsIndexed(messages, key = { _, msg -> msg.id }, contentType = { _, msg -> msg.role }) { _, msg ->
                val selected = msg.id == match
                val itemModifier = if (selected) Modifier
                    .clip(RoundedCornerShape(14.dp))
                    .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = .7f), RoundedCornerShape(14.dp))
                    .padding(6.dp) else Modifier
                Column(itemModifier.fillMaxWidth().testTag("message-turn-${msg.id}")) {
                    if (state.current.mode == Mode.Chat && msg.id == state.current.contextStart) {
                        Text(stringResource(R.string.turn_start), style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary)
                        HorizontalDivider(Modifier.padding(vertical = 4.dp))
                    }
                    if (msg.role == Role.User) {
                        UserTurn(msg, state.current, needle, vm, state.isGenerating, open)
                    } else AssistantTurn(msg, state.current, needle, vm, state.isGenerating, open)
                }
            }
            item(key = "timeline-bottom") { Spacer(Modifier.height(1.dp)) }
        }
    }
    actionSheet?.let { sheet ->
        messages.firstOrNull { it.id == actionOwner }?.let { target ->
            ChatTurnOverlay(target, state.current, vm, state.isGenerating, sheet) { actionSheet = it }
        }
    }
}

private fun Modifier.chatEdgeFades(
    bars: PaddingValues, brushes: ChatEdgeBrushes, list: LazyListState, origin: State<IntOffset?>,
    composerBoundsInWindow: () -> IntRect?,
) = drawWithCache {
    val capsule = origin.value?.let { offset -> composerBoundsInWindow()?.translate(-offset) }
        ?.takeIf { it.right > 0 && it.left < size.width }
    val bands = chatFadeBands(
        size.height, bars.calculateTopPadding().toPx(), bars.calculateBottomPadding().toPx(),
        capsule?.top?.toFloat(), capsule?.bottom?.toFloat(), density,
    )
    val bottomStart = size.height - bands.bottomLength
    val reveal = ChatEdgeFade.ScrollRevealDp.dp.toPx()
    onDrawWithContent {
        drawContent()
        // Ease the mask away as the first or last item reaches its resting padding.
        // Reading scroll position here reuses the cached brushes during a drag.
        if (bands.topLength > 0f && list.canScrollBackward) {
            val alpha = if (list.firstVisibleItemIndex > 0) 1f else (list.firstVisibleItemScrollOffset / reveal).coerceIn(0f, 1f)
            drawRect(brushes.top(bands), size = Size(size.width, bands.topLength), alpha = alpha)
        }
        if (bands.bottomLength > 0f && list.canScrollForward) {
            val info = list.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()
            val alpha = if (last != null && last.index == info.totalItemsCount - 1) {
                ((last.offset + last.size + info.afterContentPadding - info.viewportEndOffset) / reveal).coerceIn(0f, 1f)
            } else 1f
            drawRect(brushes.bottom(bands, size.height), Offset(0f, bottomStart),
                Size(size.width, bands.bottomLength), alpha = alpha)
        }
    }
}

@Composable
internal fun messageTime(time: Long, includeDate: Boolean = false): String {
    val locales = LocalConfiguration.current.locales
    return remember(time, includeDate, locales) {
        val locale = locales[0]
        val format = if (includeDate) DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT, locale)
        else DateFormat.getTimeInstance(DateFormat.SHORT, locale)
        format.format(Date(time))
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun UserTurn(msg: Message, chat: Conversation, query: String, vm: HubViewModel, busy: Boolean,
    open: (Message, TurnSheet) -> Unit) {
    val mode = chat.mode
    val colors = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End,
        verticalArrangement = Arrangement.spacedBy(if (mode == Mode.Chat) 4.dp else 0.dp)) {
        if (mode == Mode.Chat) {
            val context = LocalContext.current
            val profile by remember(context) { LocalProfileStore.shared(context).profile }.collectAsState()
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(profile.name.ifBlank { stringResource(R.string.drawer_you) }, Modifier.weight(1f, fill = false),
                    style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                ProfileAvatar(profile, Modifier.size(28.dp))
            }
            if (msg.attachments.isNotEmpty()) {
                Column(Modifier.padding(start = 24.dp), horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    msg.attachments.forEach { MessageAttachment(it, query) }
                }
            }
            if (msg.text.isNotEmpty()) {
                UserBubble(msg, query, mode, Modifier.combinedClickable(
                    onClickLabel = stringResource(if (busy) R.string.turn_more else R.string.turn_edit),
                    onClick = { open(msg, if (busy) TurnSheet.More else TurnSheet.Edit) },
                    onLongClickLabel = stringResource(R.string.turn_more), onLongClick = { open(msg, TurnSheet.More) }
                ))
            }
            ChatTurnActions(msg, chat, vm, busy, open)
        } else {
            UserBubble(msg, query, mode)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(messageTime(msg.createdAt), style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                ImageMessageActions(msg, vm, busy)
            }
        }
    }
}

@Composable
internal fun UserBubble(msg: Message, query: String, mode: Mode, modifier: Modifier = Modifier) {
    val chat = mode == Mode.Chat
    val bubble = if (chat) chatBubbleShape(Role.User) else RoundedCornerShape(22.dp, 22.dp, 6.dp, 22.dp)
    val bounds = if (chat) Modifier.padding(start = 24.dp)
        else Modifier.widthIn(max = (LocalConfiguration.current.screenWidthDp * .81f).dp)
    MessageCard(
        colors = LocalMessageStyle.current.renderPalette.user,
        modifier = bounds.testTag("message-user-bubble").clip(bubble).then(modifier),
        shape = bubble
    ) {
        Column(Modifier.padding(horizontal = if (chat) 14.dp else 16.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (mode == Mode.Image) msg.attachments.forEach { MessageAttachment(it, query) }
            if (msg.text.isNotEmpty()) {
                val text = highlighted(msg.text, query)
                if (mode == Mode.Chat) Text(text, style = MaterialTheme.typography.bodyLarge)
                else SelectionContainer { Text(text, style = MaterialTheme.typography.bodyLarge) }
            }
        }
    }
}

@Composable
internal fun AssistantTurn(msg: Message, chat: Conversation, query: String, vm: HubViewModel, busy: Boolean,
    open: (Message, TurnSheet) -> Unit) {
    val mode = chat.mode
    val colors = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(if (mode == Mode.Chat) 4.dp else 7.dp)) {
        if (mode == Mode.Image) Row(verticalAlignment = Alignment.CenterVertically) {
            IonMark(26.dp)
            Spacer(Modifier.width(8.dp))
            Text(msg.model.ifBlank { "HydrionHub" }, Modifier.weight(1f),
                style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
            Text(messageTime(msg.createdAt), style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
            ImageMessageActions(msg, vm, busy)
        } else Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (msg.remoteModel?.providerId == DEEPSEEK_PROVIDER_ID) {
                Box(Modifier.size(28.dp).clip(CircleShape).background(colors.surfaceContainerHigh),
                    contentAlignment = Alignment.Center) {
                    Image(painterResource(R.drawable.deepseek_whale), "DeepSeek", Modifier.size(21.dp))
                }
            } else IonMark(28.dp)
            Text(msg.model.ifBlank { "HydrionHub" }, style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        val thinking = msg.remoteModel != null && msg.status.isActive && msg.text.isEmpty()
        var thoughtExpanded by rememberSaveable(msg.id, query, thinking) {
            mutableStateOf(thinking || (query.isNotEmpty() && msg.thought.contains(query, ignoreCase = true)))
        }
        if (msg.thought.isNotBlank()) {
            Thought(msg, query, thoughtExpanded, onToggle = { thoughtExpanded = !thoughtExpanded })
        }
        msg.tool?.let { tool ->
            MessageCard(LocalMessageStyle.current.renderPalette.assistant, Modifier.fillMaxWidth()) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Outlined.Build, "工具调用", Modifier.size(16.dp))
                    Text(highlighted(tool, query), style = MaterialTheme.typography.labelMedium)
                }
            }
        }
        if (msg.text.isNotBlank()) AssistantBody(msg.text, query, mode)
        if (msg.imageKeys.isNotEmpty()) ImageResult(msg, query, vm, busy, showRegenerate = mode == Mode.Image)
        when (msg.status) {
            Generation.Sending, Generation.Generating -> Row(
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 1.5.dp)
                val label = when {
                    msg.status == Generation.Sending -> "正在发送…"
                    mode == Mode.Image -> "正在生成图片…"
                    else -> "正在生成…"
                }
                Text(label, style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
            }
            Generation.Error -> Row(verticalAlignment = Alignment.CenterVertically) {
                Text(highlighted(msg.error ?: "生成失败", query), Modifier.weight(1f), color = colors.error,
                    style = MaterialTheme.typography.bodySmall)
                if (mode == Mode.Image) TextButton(onClick = { vm.regenerate(msg.id) }, enabled = !busy) { Text("重试") }
            }
            Generation.Stopped -> Text(stringResource(R.string.turn_stopped), color = colors.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
            Generation.Completed -> Unit
        }
        if (mode == Mode.Chat) ChatTurnActions(msg, chat, vm, busy, open)
    }
}

@Composable
internal fun Thought(msg: Message, query: String, expanded: Boolean, onToggle: () -> Unit) {
    MessageCard(LocalMessageStyle.current.renderPalette.assistant, Modifier.fillMaxWidth().testTag("message-thought"), RoundedCornerShape(12.dp)) {
        Column {
            Row(
                Modifier.fillMaxWidth().semantics { stateDescription = if (expanded) "已展开" else "已收起" }
                    .clickable(onClickLabel = if (expanded) "收起" else "展开", onClick = onToggle)
                    .heightIn(min = 44.dp).padding(horizontal = 13.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(9.dp)
            ) {
                Icon(Icons.Outlined.Lightbulb, null, Modifier.size(17.dp))
                Text("思考过程", Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
                Icon(if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null, Modifier.size(18.dp))
            }
            if (expanded) SelectionContainer {
                Text(highlighted(msg.thought, query), Modifier.padding(start = 13.dp, end = 13.dp, bottom = 12.dp),
                    style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun MessageIcon(icon: ImageVector, label: String, enabled: Boolean = true, action: () -> Unit) {
    IconButton(onClick = action, enabled = enabled, modifier = Modifier.size(40.dp),
        colors = IconButtonDefaults.iconButtonColors(contentColor = MaterialTheme.colorScheme.onSurfaceVariant)) {
        Icon(icon, label, Modifier.size(18.dp))
    }
}

@Composable
private fun ImageMessageActions(msg: Message, vm: HubViewModel, busy: Boolean) {
    val user = msg.role == Role.User
    var menu by remember { mutableStateOf(false) }
    var dialog by rememberSaveable { mutableStateOf<String?>(null) }
    var edit by rememberSaveable { mutableStateOf("") }
    val clipboard = LocalClipboardManager.current
    val colors = MaterialTheme.colorScheme
    val copy = msg.text.ifEmpty { msg.prompt }
    val copyMessage = { clipboard.setText(AnnotatedString(copy)) }
    val openEditor = { edit = msg.text; dialog = "edit" }

    Box {
        MessageIcon(Icons.Outlined.MoreHoriz, "消息操作") { menu = true }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            if (copy.isNotBlank()) DropdownMenuItem(text = { Text(if (user) "复制文本" else "复制提示词") },
                onClick = { copyMessage(); menu = false })
            if (user) {
                DropdownMenuItem(text = { Text("编辑消息") }, enabled = !busy, onClick = { openEditor(); menu = false })
            }
            DropdownMenuItem(text = { Text("重新生成") }, enabled = !busy,
                onClick = { vm.regenerate(msg.id); menu = false })
            if (!user) DropdownMenuItem(
                text = { Text("修改提示词后再生成") }, onClick = { vm.preparePrompt(msg.id); menu = false })
            DropdownMenuItem(text = { Text("删除消息", color = colors.error) }, onClick = { dialog = "delete"; menu = false })
        }
    }
    when (dialog) {
        "delete" -> AlertDialog(onDismissRequest = { dialog = null },
            title = { Text(if (msg.role == Role.User) "删除这条消息及其回复？" else "删除这条回复？") },
            confirmButton = { TextButton(onClick = { vm.deleteMessage(msg.id); dialog = null }) { Text("删除", color = colors.error) } },
            dismissButton = { TextButton(onClick = { dialog = null }) { Text("取消") } })
        "edit" -> AlertDialog(onDismissRequest = { dialog = null }, title = { Text("编辑消息") },
            text = { OutlinedTextField(value = edit, onValueChange = { edit = it }, minLines = 2, maxLines = 6, label = { Text("消息内容") }) },
            confirmButton = { TextButton(enabled = !busy && (edit.isNotBlank() || msg.attachments.isNotEmpty()), onClick = {
                vm.editMessage(msg.id, edit); dialog = null
            }) { Text("保存") } },
            dismissButton = { TextButton(onClick = { dialog = null }) { Text("取消") } })
    }
}

internal fun isMessageAttachmentTarget(scheme: String?, authority: String?): Boolean =
    scheme == "content" && !authority.isNullOrEmpty() && !authority.endsWith('@') &&
        authority.none { it.isWhitespace() || it.isISOControl() }

internal fun openMessageAttachment(launch: () -> Unit, onFailure: (Int) -> Unit) {
    try {
        launch()
    } catch (_: ActivityNotFoundException) {
        onFailure(R.string.turn_attachment_no_viewer)
    } catch (_: SecurityException) {
        onFailure(R.string.turn_attachment_access_denied)
    }
}

@Composable
internal fun MessageAttachment(attachment: Attachment, query: String) {
    val context = LocalContext.current
    val uri = remember(attachment.uri) {
        attachment.uri?.let(Uri::parse)?.takeIf { isMessageAttachmentTarget(it.scheme, it.authority) }
    }
    val imageKey = attachment.imageKey
    if (imageKey != null) {
        val width = attachment.width?.coerceAtLeast(1) ?: 1024
        val height = attachment.height?.coerceAtLeast(1) ?: 1024
        val size = pictureSize(imageKey, Extent(width, height))
        val aspect = size?.let { it.width.toFloat() / it.height } ?: 1f
        GeneratedPicture(imageKey, width, height, attachment.name,
            Modifier.width(100.dp).aspectRatio(aspect.coerceIn(.67f, 1.75f)).clip(RoundedCornerShape(10.dp)))
    } else if (attachment.mimeType?.startsWith("image/") == true) {
        val preview = if (uri == null) PicturePreview.Failed else rememberAttachmentPreview(uri.toString())
        BitmapPreview(
            preview, attachment.name, Modifier.width(128.dp).height(90.dp).clip(RoundedCornerShape(10.dp)),
            fit = false, failureLabel = stringResource(R.string.turn_image_preview_failed),
        )
    }
    Row(
        modifier = if (uri == null) Modifier else Modifier.clip(RoundedCornerShape(8.dp)).clickable {
            openMessageAttachment(
                launch = {
                    context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, attachment.mimeType ?: "*/*")
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                },
                onFailure = { message -> Toast.makeText(context, message, Toast.LENGTH_SHORT).show() },
            )
        },
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        Icon(Icons.Outlined.AttachFile, null, Modifier.size(16.dp))
        Text(highlighted(attachment.name, query), style = MaterialTheme.typography.labelMedium,
            maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}
