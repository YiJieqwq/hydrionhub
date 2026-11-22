package io.hydrion.hub.ui

import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.rememberTransition
import androidx.compose.animation.core.tween
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
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.hydrion.hub.model.Conversation
import io.hydrion.hub.model.WorkspaceLoad
import io.hydrion.hub.model.HubViewModel
import io.hydrion.hub.model.Mode
import io.hydrion.hub.provider.ChatModelPicker
import io.hydrion.hub.provider.ChatModelBrowsePage
import io.hydrion.hub.provider.ChatModelPickerBack
import io.hydrion.hub.provider.ChatModelPickerState
import io.hydrion.hub.provider.ChatPickerEasing
import io.hydrion.hub.provider.ConfigureSheet
import io.hydrion.hub.provider.route
import io.hydrion.hub.provider.chatTriggerLabel
import io.hydrion.hub.provider.chatSpeedControls
import io.hydrion.hub.provider.chatProtocol
import io.hydrion.hub.provider.ProviderSettings
import io.hydrion.hub.ui.chrome.Header
import io.hydrion.hub.ui.chrome.contextUsage
import io.hydrion.hub.ui.composer.HubComposer
import io.hydrion.hub.ui.composer.rememberToolsPageState
import io.hydrion.hub.ui.composer.ModelEditorComposerBridge
import io.hydrion.hub.ui.messages.ProvideMessageStyle
import io.hydrion.hub.ui.messages.MessageList
import io.hydrion.hub.ui.messages.matchingMessages
import androidx.compose.ui.res.stringResource
import io.hydrion.hub.R
import io.hydrion.hub.ui.sidebar.DrawerActions
import io.hydrion.hub.ui.sidebar.DrawerHit
import io.hydrion.hub.ui.sidebar.ConversationDrawer
import io.hydrion.hub.ui.sidebar.WorkspaceDrawer
import io.hydrion.hub.ui.theme.HydrionTheme
import io.hydrion.hub.ui.theme.ProviderTheme
import kotlinx.coroutines.flow.first
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Workspace(vm: HubViewModel) {
    val providers = vm.providers
    val state by vm.state.collectAsStateWithLifecycle()
    val load by vm.workspaceLoad.collectAsStateWithLifecycle()
    // Do not expose a temporary default chat while the previous assistant is being restored.
    if (load == WorkspaceLoad.Reading) return
    val catalog by providers.catalog.collectAsStateWithLifecycle()
    val current = state.current
    val selection = remember(catalog, current.mode) { catalog.selection(current.mode) }
    val chatPicker = remember(current.id, current.mode) { ChatModelPickerState() }
    val toolsPage = if (current.mode == Mode.Chat) rememberToolsPageState(current.id) else null
    val toolsActive = toolsPage?.active == true
    val composerBridge = remember(current.id, current.mode) { ModelEditorComposerBridge() }
    val editTransition = remember { MutableTransitionState(false) }
    var editVisit by remember { mutableStateOf<ModelEditVisit?>(null) }
    val page = rememberTransition(editTransition, label = "Model configuration")
    val pageCover = page.animateFloat({ tween(ModelEditorMotionMillis, easing = ChatPickerEasing) },
        label = "Page travel and fade") { if (it) 1f else 0f }
    val ime = WindowInsets.ime
    val density = LocalDensity.current
    val keyboard = LocalSoftwareKeyboardController.current
    val view = LocalView.current
    var returnHeight by remember { mutableIntStateOf(0) }
    val returning = editVisit != null && !editTransition.targetState
    val modelBrowse = rememberModelBrowseMotion(chatPicker, returning)
    val blockEditorFocus = editVisit != null && !returning
    val chatReveal = if (!returning) Modifier else Modifier.graphicsLayer {
        translationY = returnHeight * pageCover.value
        alpha = 1f - pageCover.value
    }
    val composerReveal = editVisit?.let { visit ->
        Modifier.graphicsLayer {
            // The host already follows the actual IME. Adding a second downward slide
            // on entry puts the field behind the still-visible keyboard.
            translationY = modelVisitDockOffset(pageCover.value, visit.entryDockTravel,
                followsIme = !returning && visit.editor.imeVisible)
            alpha = 1f - pageCover.value
        }
    } ?: Modifier
    var assistantEditor by rememberSaveable { mutableStateOf<String?>(null) }
    var settings by rememberSaveable { mutableStateOf(false) }
    var configure by rememberSaveable { mutableStateOf(false) }
    var finding by rememberSaveable(current.id) { mutableStateOf(false) }
    var query by rememberSaveable(current.id) { mutableStateOf("") }
    var matchIndex by rememberSaveable(query, current.id) { mutableIntStateOf(0) }
    var drawerHit by remember { mutableStateOf<DrawerHit?>(null) }
    LaunchedEffect(current.id, drawerHit) {
        val hit = drawerHit ?: return@LaunchedEffect
        if (hit.id == current.id && hit.needle != null) {
            finding = true
            query = hit.needle
        }
    }
    // matchIndex is keyed by the query. Position the match once that query has been applied.
    LaunchedEffect(current.id, query, drawerHit) {
        val hit = drawerHit ?: return@LaunchedEffect
        if (hit.id == current.id && hit.needle == query) {
            matchIndex = matchingMessages(state, query).indexOf(hit.messageId).coerceAtLeast(0)
            drawerHit = null
        }
    }
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
    val drawerActions = remember(vm, timelines) {
        DrawerActions(vm::hasDrawerDraft, vm::setPinned, vm::moveConversations, vm::duplicateConversation,
            delete = { ids -> ids.forEach(timelines::removeState); vm.deleteConversations(ids) },
            vm::copyAssistant, vm::groupAssistant, vm::canRemoveAssistant, vm::removeEmptyAssistant)
    }
    val snackbar = remember { SnackbarHostState() }
    val notice by vm.notice.collectAsStateWithLifecycle()
    val errors by vm.errors.collectAsStateWithLifecycle()
    LaunchedEffect(notice) {
        notice?.let { snackbar.showSnackbar(it); vm.clearNotice() }
    }

    fun closeDrawer() { scope.launch { drawer.close() } }
    fun leaveDrawer(action: () -> Unit) {
        focus.clearFocus()
        // Switching modes can change the sheet width and replace its active drag anchors.
        scope.launch {
            drawer.close()
            action()
        }
    }
    fun newChat() {
        focus.clearFocus()
        vm.newConversation()
    }
    val drawerOpening = drawer.isOpen || (current.mode == Mode.Chat && drawer.targetValue == DrawerValue.Open)
    BackHandler(enabled = !settings && (drawerOpening || finding)) {
        if (drawerOpening) closeDrawer() else { finding = false; query = "" }
    }

    if (toolsPage != null && toolsActive && !settings) ChatModelPickerBack(toolsPage::close)

    if (settings) {
        // The settings screen owns back navigation between its nested editors.
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))) {
            ProviderTheme { ProviderSettings(providers, onBack = { settings = false }) }
        }
    } else {
        WorkspaceDrawer(
            mode = current.mode, drawer = drawer, onClose = ::closeDrawer, workspaceCovered = blockEditorFocus,
            gesturesEnabled = !toolsActive && !modelBrowse.active,
            drawerContent = { width, visible ->
                ConversationDrawer(
                    width = width,
                    containerColor = if (current.mode == Mode.Chat) MaterialTheme.colorScheme.background
                        else MaterialTheme.colorScheme.surfaceContainerLow,
                    history = if (visible) state.history else emptyList(),
                    assistants = state.assistants, assistantId = current.assistantId, assistantsReady = load == WorkspaceLoad.Ready,
                    onSelectAssistant = { id -> leaveDrawer { vm.selectAssistant(id) } },
                    onRenameAssistant = vm::renameAssistant, currentId = current.id, onNew = { leaveDrawer(vm::newConversation) },
                    onOpen = { id -> leaveDrawer { vm.openConversation(id) } },
                    onSettings = { closeDrawer(); settings = true }, onAssistantSettings = { assistantEditor = it },
                    onRename = { renameId = it }, onPin = vm::togglePin, onDelete = { deleteId = it }, actions = drawerActions,
                    onHit = { hit -> leaveDrawer { vm.openConversation(hit.id); drawerHit = hit.takeIf { it.needle != null } } }
                )
            }
        ) {
            val composerCatalog = catalog
            val selectedEntry = remember(composerCatalog, current.mode) { composerCatalog.selectedEntry(current.mode) }
            // Legacy IMEs can remove the entire inset between composition and layout.
            // Place the host using the same inset frame that determines the composer shape.
            val imeInsets = WindowInsets.ime
            val hostImeInsets = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R)
                WindowInsets(bottom = imeInsets.getBottom(LocalDensity.current)) else imeInsets
            val workspaceInput = if (chatPicker.page == ChatModelPickerState.Page.Controls)
                chatPicker.effortTouches.workspace(Modifier, chatPicker) else Modifier
            Box(
                Modifier.fillMaxSize().then(workspaceInput)
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
                    .windowInsetsPadding(hostImeInsets),
                contentAlignment = Alignment.TopCenter
            ) {
                val covered = toolsActive || modelBrowse.active
                ChatComposerLayers(
                    modifier = Modifier.fillMaxSize(),
                    panelActive = covered, panelSettled = toolsPage?.interactive == true || modelBrowse.interactive,
                    freezeTimelineDock = covered && editVisit == null, dockKey = chatPicker,
                    trackPickerAnchor = { editVisit == null && (chatPicker.page != null || modelBrowse.active || chatPicker.drawingBubble) },
                    picker = if (current.mode == Mode.Chat) {
                        { ChatModelPicker(providers, chatPicker, suspended = editVisit != null, hideForReturn = returning,
                            modelBrowseProgress = { modelBrowse.progress }, modifier = Modifier.fillMaxSize()) }
                    } else null,
                    composer = {
                        val bottomBars = WindowInsets.safeDrawing
                        Column(composerReveal.windowInsetsPadding(bottomBars.only(WindowInsetsSides.Bottom))) {
                            key(current.id) {
                                errors[current.id]?.let { detail -> ComposerError(detail) { vm.clearError(current.id) } }
                                // Keep the editor at the same composition position in both modes.
                                val chipLabel = catalog.chatTriggerLabel(
                                    selectedEntry.takeIf { current.mode == Mode.Chat }, selection.label, chatPicker.previewFor(selectedEntry?.route),
                                )
                                HubComposer(vm, state, chipLabel, selection.available,
                                    fast = remember(selectedEntry, selection.parameters) {
                                        selectedEntry?.let { (model, provider) ->
                                            model.chatSpeedControls(provider, selection.parameters).selected != null
                                        } == true
                                    },
                                    chatPicker = chatPicker.takeIf { current.mode == Mode.Chat },
                                    modelEditorBridge = composerBridge,
                                    chatTools = toolsPage,
                                    modelBrowseProgress = { modelBrowse.progress },
                                    allowImeReturn = !blockEditorFocus && !drawerOpening && !configure && !finding && renameId == null && deleteId == null,
                                    toolsAvailable = selectedEntry?.second?.chatProtocol() == null,
                                    onConfigure = { focus.clearFocus(); configure = true },
                                    onSend = { fromDictation ->
                                        // Dictation has already written the final transcript to the live draft.
                                        val submitted = vm.state.value
                                        if (submitted.current.id == current.id && submitted.current.mode == current.mode) {
                                            scope.launch {
                                                sendDraft(vm, providers, submitted, composerCatalog, fromDictation)
                                                    ?.let { vm.reportError(current.id, it) }
                                            }
                                        }
                                    })
                            }
                        }
                    },
                ) { timelineDock, panelDock, excessDockPx ->
                    Scaffold(
                        modifier = Modifier.fillMaxSize().behindToolsPage(covered),
                        contentWindowInsets = WindowInsets(0, 0, 0, 0),
                        topBar = {
                            Column(chatReveal) {
                                Box(Modifier.chatPageHeader {
                                    if (modelBrowse.active) modelBrowse.progress else toolsPage?.progress ?: 0f
                                }.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top))) {
                                    Header(
                                        mode = current.mode, locked = state.modeLocked, title = current.title, pinned = current.pinned,
                                        contextUsage = contextUsage(current, selectedEntry),
                                        onMode = vm::setMode, onNew = ::newChat,
                                        onDrawer = { focus.clearFocus(); scope.launch { drawer.open() } },
                                        onRename = { renameId = current.id }, onPin = { vm.togglePin(current.id) },
                                        onFind = { finding = true }, onDelete = { deleteId = current.id }
                                    )
                                }
                                if (finding) FindBar(query, { query = it }, index, hits.size,
                                    previous = { if (hits.isNotEmpty()) matchIndex = (index - 1 + hits.size) % hits.size },
                                    next = { if (hits.isNotEmpty()) matchIndex = (index + 1) % hits.size },
                                    onClose = { finding = false; query = ""; focus.clearFocus() })
                            }
                        },
                        bottomBar = { Spacer(Modifier.height(timelineDock)) },
                        snackbarHost = { SnackbarHost(snackbar) }
                    ) { bars ->
                        if (current.messages.isNotEmpty()) {
                            timelines.SaveableStateProvider(current.id) {
                                MessageList(vm, state, if (finding) query else "", index, modifier = chatReveal, floatingBars = bars,
                                    composerBoundsInWindow = { chatPicker.composerBounds })
                            }
                        } else if (current.mode == Mode.Image && !state.modeLocked &&
                            state.draft.text.isBlank() && state.draft.attachments.isEmpty()) {
                            RecentImageConversations(
                                history = state.history,
                                onOpen = { id -> focus.clearFocus(); vm.openConversation(id) },
                                modifier = Modifier.fillMaxSize().padding(bars)
                            )
                        }
                    }
                    if (toolsPage != null && toolsPage.hasOpened) {
                        key(toolsPage) {
                            ToolsPageOverlay(toolsPage, state.draft.search, state.draft.imageTool, panelDock,
                                excessDockPx, vm::setSearch, vm::setImageTool)
                        }
                    }
                    modelBrowse.page?.let {
                        ChatSlidingPage({ modelVisitBrowseReveal(modelBrowse.progress, pageCover.value) }, modelBrowse.interactive && editVisit == null,
                            "chat-model-browse-surface") {
                            ChatModelBrowsePage(providers, chatPicker, suspended = editVisit != null,
                                hideForReturn = returning, composerPadding = panelDock,
                                modifier = Modifier.clipSlidingPageContent(excessDockPx),
                                onEditModel = edit@{ route, browse ->
                                    val origin = vm.state.value
                                    if (editVisit != null || origin.current.id != current.id) return@edit
                                    val visibleBottom = view.height - ime.getBottom(density)
                                    val dockTravel = (visibleBottom - (chatPicker.composerBounds?.top ?: visibleBottom)).coerceAtLeast(0)
                                    val editor = composerBridge.captureAndDismiss() ?: return@edit
                                    editVisit = ModelEditVisit(route, current.id, origin.draft, browse, editor, dockTravel.toFloat())
                                    editTransition.targetState = true
                                })
                        }
                    }
                }
            }
        }
    }

    editVisit?.let { visit ->
        var earlyBack by remember(visit) { mutableStateOf(false) }
        fun startReturn() {
            returnHeight = view.height
            if (!visit.editor.imeVisible) keyboard?.hide()
            focus.clearFocus(force = true)
            vm.restoreModelPickerDraft(visit.conversationId, visit.draft)
            editTransition.targetState = false
        }
        ModelEditorOverlay(providers, visit, returning, pageCover,
            composerBounds = { chatPicker.composerBounds }) {
            if (editTransition.targetState) {
                if (editTransition.isIdle) startReturn() else earlyBack = true
            }
        }
        if (earlyBack) LaunchedEffect(visit) {
            // A second back during entry queues one return after the configuration fully covers the source.
            snapshotFlow { editTransition.isIdle }.first { it }
            if (editTransition.targetState) startReturn()
            earlyBack = false
        }
        LaunchedEffect(visit, editTransition.targetState, current.id) {
            if (editTransition.targetState) return@LaunchedEffect
            // A return to another live conversation installs its picker/bridge on the next composition.
            if (current.id != visit.conversationId && vm.state.value.current.id == visit.conversationId) return@LaunchedEffect
            if (current.id == visit.conversationId) {
                chatPicker.restoreBrowsing(visit.browse)
                composerBridge.restore(visit.editor)
                snapshotFlow { editTransition.isIdle && chatPicker.pendingBrowseRestore == null }.first { it }
            } else snapshotFlow { editTransition.isIdle }.first { it }
            editVisit = null
        }
    }

    if (configure) ConfigureSheet(providers, current.mode) { configure = false }
    fun titleFor(id: String) = if (id == current.id) current.title else state.history.firstOrNull { it.id == id }?.title.orEmpty()
    assistantEditor?.let { id ->
        io.hydrion.hub.ui.settings.AssistantDetail(vm, id) { assistantEditor = null }
    }
    renameId?.let { id ->
        var value by rememberSaveable(id) { mutableStateOf(titleFor(id)) }
        AlertDialog(onDismissRequest = { renameId = null }, title = { Text(stringResource(R.string.drawer_rename)) }, text = {
            OutlinedTextField(value, { value = it }, label = { Text(stringResource(R.string.drawer_title)) }, singleLine = true)
        }, confirmButton = {
            TextButton(onClick = { vm.rename(id, value.trim()); renameId = null }, enabled = value.isNotBlank()) { Text(stringResource(R.string.settings_save)) }
        }, dismissButton = { TextButton(onClick = { renameId = null }) { Text(stringResource(R.string.settings_cancel)) } })
    }
    deleteId?.let { id ->
        AlertDialog(onDismissRequest = { deleteId = null }, title = { Text(stringResource(R.string.drawer_delete)) },
            text = { Text(stringResource(R.string.drawer_delete_one, titleFor(id))) },
            confirmButton = { TextButton(onClick = { timelines.removeState(id); vm.deleteConversation(id); deleteId = null }) {
                Text(stringResource(R.string.drawer_delete), color = MaterialTheme.colorScheme.error)
            } }, dismissButton = { TextButton(onClick = { deleteId = null }) { Text(stringResource(R.string.settings_cancel)) } })
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
private fun RecentImageConversations(
    history: List<Conversation>, onOpen: (String) -> Unit, modifier: Modifier
) {
    val recent = remember(history) {
        history.asSequence().filter { it.mode == Mode.Image }.take(3).toList()
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
                    Icon(Icons.Outlined.Image,
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
