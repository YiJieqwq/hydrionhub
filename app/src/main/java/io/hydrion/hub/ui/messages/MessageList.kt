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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalContext
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
    matchIndex: Int,
    modifier: Modifier = Modifier,
    floatingBars: PaddingValues = PaddingValues(0.dp),
    composerBoundsInWindow: () -> IntRect? = { null },
) {
    val messages = state.current.messages
    val needle = searchQuery.trim()
    val match = remember(messages, needle, matchIndex) { matchingMessages(state, needle).getOrNull(matchIndex) }
    val list = rememberLazyListState()
    var followBottom by rememberSaveable { mutableStateOf(true) }
    // A drag/fling away from the end takes ownership of the viewport until the user returns.
    LaunchedEffect(list) {
        snapshotFlow { list.isScrollInProgress to list.canScrollForward }.collect { (moving, hasMore) ->
            if (moving) followBottom = !hasMore
        }
    }
    LaunchedEffect(match, needle) {
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
    MessageCanvas(LocalMessageStyle.current, modifier.fillMaxSize()) {
        val edges = if (state.current.mode == Mode.Chat) {
            val origin = remember { mutableStateOf<IntOffset?>(null) }
            Modifier.onGloballyPositioned { origin.value = it.positionInWindow().round() }
                .chatEdgeFades(floatingBars, MaterialTheme.colorScheme.background, list, origin, composerBoundsInWindow)
        } else Modifier
        LazyColumn(
            state = list, modifier = Modifier.fillMaxSize().testTag("message-timeline").then(edges), contentPadding = padding,
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            itemsIndexed(messages, key = { _, msg -> msg.id }, contentType = { _, msg -> msg.role }) { index, msg ->
                val selected = msg.id == match
                val itemModifier = if (selected) Modifier
                    .clip(RoundedCornerShape(14.dp))
                    .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = .7f), RoundedCornerShape(14.dp))
                    .padding(6.dp) else Modifier
                Box(itemModifier.fillMaxWidth().testTag("message-turn-${msg.id}")) {
                    if (msg.role == Role.User) {
                        val hasReply = messages.getOrNull(index + 1)?.replyTo == msg.id
                        UserTurn(msg, state.current.mode, needle, vm, state.isGenerating, hasReply)
                    } else AssistantTurn(msg, state.current.mode, needle, vm, state.isGenerating)
                }
            }
            item(key = "timeline-bottom") { Spacer(Modifier.height(1.dp)) }
        }
    }
}

private fun Modifier.chatEdgeFades(
    bars: PaddingValues, background: Color, list: LazyListState, origin: State<IntOffset?>,
    composerBoundsInWindow: () -> IntRect?,
) = drawWithCache {
    val capsule = origin.value?.let { offset -> composerBoundsInWindow()?.translate(-offset) }
        ?.takeIf { it.right > 0 && it.left < size.width }
    val bands = chatFadeBands(
        size.height, bars.calculateTopPadding().toPx(), bars.calculateBottomPadding().toPx(),
        capsule?.top?.toFloat(), capsule?.bottom?.toFloat(), density,
    )
    val clear = background.copy(alpha = 0f)
    val header = bands.headerStop
    val topBrush = if (bands.topLength > 0f) Brush.verticalGradient(
        0f to background, header * .45f to background.copy(alpha = .94f),
        header to background.copy(alpha = .52f), (header + (1f - header) * .70f) to background.copy(alpha = .10f),
        1f to clear, endY = bands.topLength,
    ) else null
    val bottomStart = size.height - bands.bottomLength
    val bottomBrush = if (bands.bottomLength > 0f) {
        val stops = buildList {
            add(0f to clear)
            add(bands.capsuleTopStop to background.copy(alpha = .45f))
            add(bands.capsuleBottomStop to background.copy(alpha = .88f))
            if (bands.capsuleBottomStop < 1f) add(1f to background)
        }
        Brush.verticalGradient(*stops.toTypedArray(), startY = bottomStart, endY = size.height)
    } else null
    val reveal = 24.dp.toPx()
    onDrawWithContent {
        drawContent()
        // Ease the mask away as the first or last item reaches its resting padding.
        // Reading scroll position here reuses the cached brushes during a drag.
        if (topBrush != null && list.canScrollBackward) {
            val alpha = if (list.firstVisibleItemIndex > 0) 1f else (list.firstVisibleItemScrollOffset / reveal).coerceIn(0f, 1f)
            drawRect(topBrush, size = Size(size.width, bands.topLength), alpha = alpha)
        }
        if (bottomBrush != null && list.canScrollForward) {
            val info = list.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()
            val alpha = if (last != null && last.index == info.totalItemsCount - 1) {
                ((last.offset + last.size + info.afterContentPadding - info.viewportEndOffset) / reveal).coerceIn(0f, 1f)
            } else 1f
            drawRect(bottomBrush, Offset(0f, bottomStart), Size(size.width, bands.bottomLength), alpha = alpha)
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
private fun UserTurn(msg: Message, mode: Mode, query: String, vm: HubViewModel, busy: Boolean, hasReply: Boolean) {
    val colors = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End) {
        if (mode == Mode.Chat) {
            MessageActions(msg, mode, vm, busy, hasReply = hasReply) { open ->
                UserBubble(msg, query, mode, Modifier.combinedClickable(
                    onClickLabel = "消息操作", onClick = open,
                    onLongClickLabel = "消息操作", onLongClick = open
                ))
            }
        } else {
            UserBubble(msg, query, mode)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(messageTime(msg.createdAt), style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                MessageActions(msg, mode, vm, busy)
            }
        }
    }
}

@Composable
internal fun UserBubble(msg: Message, query: String, mode: Mode, modifier: Modifier = Modifier) {
    val bubble = if (mode == Mode.Chat) RoundedCornerShape(23.dp) else RoundedCornerShape(22.dp, 22.dp, 6.dp, 22.dp)
    val available = LocalConfiguration.current.screenWidthDp
    MessageCard(
        colors = LocalMessageStyle.current.renderPalette.user,
        modifier = Modifier.widthIn(max = (available * .81f).dp).testTag("message-user-bubble").clip(bubble).then(modifier),
        shape = bubble
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            msg.attachments.forEach { MessageAttachment(it, query) }
            if (msg.text.isNotEmpty()) {
                val text = highlighted(msg.text, query)
                if (mode == Mode.Chat) Text(text, style = MaterialTheme.typography.bodyLarge)
                else SelectionContainer { Text(text, style = MaterialTheme.typography.bodyLarge) }
            }
        }
    }
}

@Composable
private fun AssistantTurn(msg: Message, mode: Mode, query: String, vm: HubViewModel, busy: Boolean) {
    val colors = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(7.dp)) {
        if (mode == Mode.Image) Row(verticalAlignment = Alignment.CenterVertically) {
            IonMark(26.dp)
            Spacer(Modifier.width(8.dp))
            Text(msg.model.ifBlank { "HydrionHub" }, Modifier.weight(1f),
                style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
            Text(messageTime(msg.createdAt), style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
            MessageActions(msg, mode, vm, busy)
        } else Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            if (msg.remoteModel?.providerId == DEEPSEEK_PROVIDER_ID) {
                Box(Modifier.size(26.dp).clip(CircleShape).background(colors.surfaceContainerHigh),
                    contentAlignment = Alignment.Center) {
                    Image(painterResource(R.drawable.deepseek_whale), "DeepSeek", Modifier.size(21.dp))
                }
            } else IonMark(22.dp)
            Text(msg.model.ifBlank { "HydrionHub" }, Modifier.weight(1f, fill = false),
                style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Medium,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(messageTime(msg.createdAt), color = colors.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
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
        if (msg.text.isNotBlank()) AssistantBody(msg.text, query)
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
            Generation.Stopped -> Text("已停止", color = colors.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
            Generation.Completed -> Unit
        }
        if (mode == Mode.Chat) MessageActions(msg, mode, vm, busy)
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
private fun MessageActions(
    msg: Message,
    mode: Mode,
    vm: HubViewModel,
    busy: Boolean,
    hasReply: Boolean = false,
    bubble: @Composable ((() -> Unit) -> Unit)? = null
) {
    val chat = mode == Mode.Chat
    val user = msg.role == Role.User
    var menu by remember { mutableStateOf(false) }
    var dialog by rememberSaveable { mutableStateOf<String?>(null) }
    var edit by rememberSaveable { mutableStateOf("") }
    val clipboard = LocalClipboardManager.current
    val colors = MaterialTheme.colorScheme
    val copy = if (chat) msg.text else msg.text.ifEmpty { msg.prompt }
    val copyMessage = { clipboard.setText(AnnotatedString(copy)) }
    val openEditor = { edit = msg.text; dialog = "edit" }

    Column(horizontalAlignment = if (user) Alignment.End else Alignment.Start) {
        bubble?.invoke { menu = true }
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (chat) {
                if (copy.isNotBlank()) MessageIcon(Icons.Outlined.ContentCopy,
                    if (user) "复制消息" else "复制回复", action = copyMessage)
                if (user) MessageIcon(Icons.Outlined.Edit, "编辑消息", enabled = !busy, action = openEditor)
                else MessageIcon(Icons.Outlined.Refresh, "重新生成回复", enabled = !busy) { vm.regenerate(msg.id) }
            }
            Box {
                if (bubble == null) MessageIcon(Icons.Outlined.MoreHoriz,
                    if (chat && !user) "回复操作" else "消息操作") { menu = true }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    if (!chat && copy.isNotBlank()) DropdownMenuItem(text = { Text(if (user) "复制文本" else "复制提示词") },
                        onClick = { copyMessage(); menu = false })
                    if ((!chat && user) || (chat && !user && msg.text.isNotBlank())) {
                        DropdownMenuItem(text = { Text("编辑消息") }, enabled = !busy, onClick = { openEditor(); menu = false })
                    }
                    if (!chat || (user && !hasReply)) DropdownMenuItem(text = { Text("重新生成") }, enabled = !busy,
                        onClick = { vm.regenerate(msg.id); menu = false })
                    if (!user && (!chat || msg.imageKeys.isNotEmpty())) DropdownMenuItem(
                        text = { Text("修改提示词后再生成") }, onClick = { vm.preparePrompt(msg.id); menu = false })
                    DropdownMenuItem(text = { Text("删除消息", color = colors.error) }, onClick = { dialog = "delete"; menu = false })
                }
            }
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

@Composable
private fun MessageAttachment(attachment: Attachment, query: String) {
    val context = LocalContext.current
    val uri = attachment.uri
    val imageKey = attachment.imageKey
    if (imageKey != null) {
        val width = attachment.width?.coerceAtLeast(1) ?: 1024
        val height = attachment.height?.coerceAtLeast(1) ?: 1024
        val size = pictureSize(imageKey, Extent(width, height))
        val aspect = size?.let { it.width.toFloat() / it.height } ?: 1f
        GeneratedPicture(imageKey, width, height, attachment.name,
            Modifier.width(100.dp).aspectRatio(aspect.coerceIn(.67f, 1.75f)).clip(RoundedCornerShape(10.dp)))
    } else if (uri != null && attachment.mimeType?.startsWith("image/") == true) {
        AttachmentPicture(uri, Modifier.width(128.dp).height(90.dp).clip(RoundedCornerShape(10.dp)))
    }
    Row(
        modifier = if (uri == null) Modifier else Modifier.clip(RoundedCornerShape(8.dp)).clickable {
            val fileUri = Uri.parse(uri)
            if (fileUri.scheme == "content") {
                try {
                    context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(fileUri, attachment.mimeType ?: "*/*")
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                } catch (_: ActivityNotFoundException) {
                    Toast.makeText(context, "未找到可打开附件的应用", Toast.LENGTH_SHORT).show()
                } catch (_: SecurityException) {
                    Toast.makeText(context, "附件访问权限已失效", Toast.LENGTH_SHORT).show()
                }
            }
        },
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        Icon(Icons.Outlined.AttachFile, null, Modifier.size(16.dp))
        Text(highlighted(attachment.name, query), style = MaterialTheme.typography.labelMedium,
            maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}
