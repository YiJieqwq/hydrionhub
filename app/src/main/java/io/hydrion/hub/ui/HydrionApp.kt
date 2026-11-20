package io.hydrion.hub.ui

import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.hydrion.hub.model.Conversation
import io.hydrion.hub.model.HubViewModel
import io.hydrion.hub.model.Mode
import io.hydrion.hub.provider.ChatModelPicker
import io.hydrion.hub.provider.ChatModelPickerState
import io.hydrion.hub.provider.ConfigureSheet
import io.hydrion.hub.provider.chatProtocol
import io.hydrion.hub.provider.ProviderSettings
import io.hydrion.hub.ui.chrome.ChatHeaderLayout
import io.hydrion.hub.ui.chrome.Header
import io.hydrion.hub.ui.chrome.contextUsage
import io.hydrion.hub.ui.composer.HubComposer
import io.hydrion.hub.ui.messages.ProvideMessageStyle
import io.hydrion.hub.ui.messages.MessageList
import io.hydrion.hub.ui.messages.matchingMessages
import io.hydrion.hub.ui.sidebar.ConversationDrawer
import io.hydrion.hub.ui.sidebar.WorkspaceDrawer
import io.hydrion.hub.ui.theme.HydrionTheme
import io.hydrion.hub.ui.theme.ProviderTheme
import kotlinx.coroutines.launch

@Composable
fun HydrionApp() {
    HydrionTheme {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background,
            contentColor = MaterialTheme.colorScheme.onBackground) {
            ProvideMessageStyle { Workspace(viewModel()) }
        }
    }
}

@Composable
private fun Workspace(vm: HubViewModel) {
    val providers = vm.providers
    val state by vm.state.collectAsStateWithLifecycle()
    val catalog by providers.catalog.collectAsStateWithLifecycle()
    val current = state.current
    val selection = catalog.selection(current.mode)
    val chatPicker = remember(current.id, current.mode) { ChatModelPickerState() }
    var settings by rememberSaveable { mutableStateOf(false) }
    var configure by rememberSaveable { mutableStateOf(false) }
    var finding by rememberSaveable(current.id) { mutableStateOf(false) }
    var query by rememberSaveable(current.id) { mutableStateOf("") }
    var matchIndex by rememberSaveable(query, current.id) { mutableIntStateOf(0) }
    val hits = remember(query, current.messages, finding) {
        if (finding && query.isNotBlank()) matchingMessages(state, query) else emptyList()
    }
    val index = matchIndex.coerceIn(0, (hits.size - 1).coerceAtLeast(0))
    var renameId by rememberSaveable { mutableStateOf<String?>(null) }
    var deleteId by rememberSaveable { mutableStateOf<String?>(null) }
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val focus = LocalFocusManager.current
    val timelines = rememberSaveableStateHolder()
    val snackbar = remember { SnackbarHostState() }
    val notice by vm.notice.collectAsStateWithLifecycle()
    val errors by vm.errors.collectAsStateWithLifecycle()
    LaunchedEffect(notice) {
        notice?.let { snackbar.showSnackbar(it); vm.clearNotice() }
    }

    fun closeDrawer() { scope.launch { drawer.close() } }
    fun newChat() {
        closeDrawer()
        focus.clearFocus()
        vm.newConversation()
    }
    val drawerOpening = drawer.isOpen || (current.mode == Mode.Chat && drawer.targetValue == DrawerValue.Open)
    BackHandler(enabled = !settings && (drawerOpening || finding)) {
        if (drawerOpening) closeDrawer() else { finding = false; query = "" }
    }

    if (settings) {
        // The settings screen owns back navigation between its nested editors.
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))) {
            ProviderTheme { ProviderSettings(providers, onBack = { settings = false }) }
        }
    } else {
        WorkspaceDrawer(
            mode = current.mode, drawer = drawer, onClose = ::closeDrawer,
            drawerContent = { width, visible ->
                ConversationDrawer(
                    width = width,
                    containerColor = if (current.mode == Mode.Chat) MaterialTheme.colorScheme.background
                        else MaterialTheme.colorScheme.surfaceContainerLow,
                    history = if (visible) state.history else emptyList(),
                    currentId = current.id, onNew = ::newChat,
                    onOpen = { vm.openConversation(it); focus.clearFocus(); closeDrawer() },
                    onSettings = { closeDrawer(); settings = true },
                    onRename = { renameId = it }, onPin = vm::togglePin, onDelete = { deleteId = it }
                )
            }
        ) {
            val selectedEntry = remember(catalog, current.mode) { catalog.selectedEntry(current.mode) }
            // Legacy IMEs can remove the entire inset between composition and layout.
            // Place the host using the same inset frame that determines the composer shape.
            val imeInsets = WindowInsets.ime
            val hostImeInsets = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R)
                WindowInsets(bottom = imeInsets.getBottom(LocalDensity.current)) else imeInsets
            Box(
                Modifier.fillMaxSize()
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
                    .windowInsetsPadding(hostImeInsets),
                contentAlignment = Alignment.TopCenter
            ) {
                Scaffold(
                    modifier = Modifier.widthIn(max = ChatHeaderLayout.MaxWidth).fillMaxSize(),
                    contentWindowInsets = WindowInsets(0, 0, 0, 0),
                    topBar = {
                        Column(Modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top))) {
                            Header(
                                mode = current.mode, locked = state.modeLocked, title = current.title, pinned = current.pinned,
                                contextUsage = contextUsage(current, selectedEntry),
                                onMode = vm::setMode, onNew = ::newChat,
                                onDrawer = { focus.clearFocus(); scope.launch { drawer.open() } },
                                onRename = { renameId = current.id }, onPin = { vm.togglePin(current.id) },
                                onFind = { finding = true }, onDelete = { deleteId = current.id }
                            )
                            if (finding) FindBar(query, { query = it }, index, hits.size,
                                previous = { if (hits.isNotEmpty()) matchIndex = (index - 1 + hits.size) % hits.size },
                                next = { if (hits.isNotEmpty()) matchIndex = (index + 1) % hits.size },
                                onClose = { finding = false; query = ""; focus.clearFocus() })
                        }
                    },
                    bottomBar = {
                        Column(Modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom))) {
                            key(current.id) {
                                errors[current.id]?.let { detail -> ComposerError(detail) { vm.clearError(current.id) } }
                                val chipLabel = if (current.mode == Mode.Chat)
                                    listOfNotNull(selection.label, selection.parameters["effort"]).joinToString(" ")
                                else selection.label
                                HubComposer(vm, state, chipLabel, selection.available,
                                    fast = current.mode == Mode.Chat && selection.parameters["speed"] == "Fast",
                                    chatPicker = chatPicker.takeIf { current.mode == Mode.Chat },
                                    allowImeReturn = !drawerOpening && !configure && !finding && renameId == null && deleteId == null,
                                    toolsAvailable = selectedEntry?.second?.chatProtocol() == null,
                                    onConfigure = { focus.clearFocus(); configure = true },
                                    onSend = {
                                        scope.launch {
                                            sendDraft(vm, providers)?.let { vm.reportError(current.id, it) }
                                        }
                                    })
                            }
                        }
                    },
                    snackbarHost = { SnackbarHost(snackbar) }
                ) { bars ->
                    if (current.messages.isEmpty()) {
                        RecentConversations(
                            history = state.history, mode = current.mode,
                            visible = !state.modeLocked && state.draft.text.isBlank() && state.draft.attachments.isEmpty(),
                            onOpen = { id -> focus.clearFocus(); vm.openConversation(id) },
                            modifier = Modifier.fillMaxSize().padding(bars)
                        )
                    } else {
                        timelines.SaveableStateProvider(current.id) {
                            MessageList(vm, state, if (finding) query else "", index, floatingBars = bars)
                        }
                    }
                }
                if (current.mode == Mode.Chat && chatPicker.page != null) {
                    ChatModelPicker(providers, chatPicker, Modifier.matchParentSize())
                }
            }
        }
    }

    if (configure) ConfigureSheet(providers, current.mode) { configure = false }
    fun titleFor(id: String) = if (id == current.id) current.title else state.history.firstOrNull { it.id == id }?.title.orEmpty()
    renameId?.let { id ->
        var value by rememberSaveable(id) { mutableStateOf(titleFor(id)) }
        AlertDialog(onDismissRequest = { renameId = null }, title = { Text("重命名会话") }, text = {
            OutlinedTextField(value, { value = it }, label = { Text("会话名称") }, singleLine = true)
        }, confirmButton = {
            TextButton(onClick = { vm.rename(id, value.trim()); renameId = null }, enabled = value.isNotBlank()) { Text("保存") }
        }, dismissButton = { TextButton(onClick = { renameId = null }) { Text("取消") } })
    }
    deleteId?.let { id ->
        AlertDialog(onDismissRequest = { deleteId = null }, title = { Text("删除会话？") },
            text = { Text("将从本机删除「${titleFor(id)}」及其消息。") },
            confirmButton = { TextButton(onClick = { timelines.removeState(id); vm.deleteConversation(id); deleteId = null }) {
                Text("删除", color = MaterialTheme.colorScheme.error)
            } }, dismissButton = { TextButton(onClick = { deleteId = null }) { Text("取消") } })
    }
}

@Composable
private fun ComposerError(detail: String, onDismiss: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    Surface(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp).testTag("composer-error"),
        shape = MaterialTheme.shapes.medium, color = Color(0xFF9C1724), contentColor = Color.White
    ) {
        Row(Modifier.padding(start = 12.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("消息发送失败", style = MaterialTheme.typography.titleSmall)
                Text(detail, style = MaterialTheme.typography.bodySmall, maxLines = 4, overflow = TextOverflow.Ellipsis)
            }
            IconButton(onClick = { clipboard.setText(AnnotatedString(detail)) }) {
                Icon(Icons.Outlined.ContentCopy, "复制错误", Modifier.size(18.dp))
            }
            IconButton(onClick = onDismiss) { Icon(Icons.Outlined.Close, "关闭错误提示", Modifier.size(18.dp)) }
        }
    }
}

@Composable
private fun RecentConversations(
    history: List<Conversation>, mode: Mode, visible: Boolean, onOpen: (String) -> Unit, modifier: Modifier
) {
    val recent = remember(history, mode, visible) {
        if (visible) history.asSequence().filter { it.mode == mode }.take(3).toList() else emptyList()
    }
    BoxWithConstraints(modifier, contentAlignment = Alignment.BottomCenter) {
        val count = (maxHeight.value / 46f).toInt().coerceIn(0, recent.size)
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            recent.take(count).forEach { conversation ->
                Row(Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small)
                    .clickable(onClickLabel = "打开会话") { onOpen(conversation.id) }
                    .heightIn(min = 46.dp).padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(if (mode == Mode.Chat) Icons.Outlined.ChatBubbleOutline else Icons.Outlined.Image,
                        contentDescription = null, modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(conversation.title, style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
private fun FindBar(query: String, onQuery: (String) -> Unit, index: Int, count: Int,
                    previous: () -> Unit, next: () -> Unit, onClose: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh, MaterialTheme.shapes.large)
            .padding(horizontal = 6.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        OutlinedTextField(query, onQuery, Modifier.weight(1f), placeholder = { Text("查找聊天内容") }, singleLine = true)
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            if (query.isNotBlank()) Text(if (count == 0) "0/0" else "${index + 1}/$count",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row {
                IconButton(previous, enabled = count > 1, modifier = Modifier.size(36.dp)) { Icon(Icons.Outlined.ExpandLess, "上一处") }
                IconButton(next, enabled = count > 1, modifier = Modifier.size(36.dp)) { Icon(Icons.Outlined.ExpandMore, "下一处") }
            }
        }
        IconButton(onClose, Modifier.size(38.dp)) { Icon(Icons.Outlined.Close, "关闭查找") }
    }
}
