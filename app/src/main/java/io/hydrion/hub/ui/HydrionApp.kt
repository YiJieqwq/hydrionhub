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
import io.hydrion.hub.ui.chrome.ContextWindowOverlay
import io.hydrion.hub.ui.chrome.ContextWindowState
import io.hydrion.hub.ui.chrome.contextWindowInput
import io.hydrion.hub.ui.chrome.conversationEnvironmentName
import io.hydrion.hub.ui.chrome.contextUsage
import io.hydrion.hub.ui.composer.ChatToolsPage
import androidx.compose.ui.draw.drawBehind
import androidx.compose.foundation.interaction.MutableInteractionSource
import io.hydrion.hub.ui.composer.HubComposer
import io.hydrion.hub.ui.composer.rememberToolsPageState
import io.hydrion.hub.ui.composer.ModelEditorComposerBridge
import io.hydrion.hub.ui.messages.ProvideMessageStyle
import io.hydrion.hub.ui.messages.containsVisibleBody
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

internal data class ConversationFind(
    val open: Boolean = false,
    val query: String = "",
    val messageId: String? = null,
    // Reopening the same result must scroll again even when its message and query are unchanged.
    val jump: Long = 0,
) : java.io.Serializable {
    fun matchIndex(matches: List<String>): Int = messageId?.let(matches::indexOf) ?: 0

    fun openHit(conversation: Conversation, hit: DrawerHit): ConversationFind {
        if (conversation.id != hit.id) return this
        val needle = hit.needle ?: return ConversationFind()
        val message = conversation.messages.find { it.id == hit.messageId } ?: return ConversationFind()
        return if (message.containsVisibleBody(needle)) ConversationFind(true, needle, message.id, jump + 1) else ConversationFind()
    }
}

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
    // Keep drawer motion in the workspace when the conversation's keyed content is replaced.
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val drawerScope = rememberCoroutineScope()
    val catalog by providers.catalog.collectAsStateWithLifecycle()
    val current = state.current
    val contextWindow = remember(current.id, current.mode) { ContextWindowState() }
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
    val returning = editVisit != null && !editTransition.targetState
    val modelBrowse = rememberModelBrowseMotion(chatPicker, returning)
    val blockEditorFocus = editVisit != null && !returning
    var assistantEditor by rememberSaveable { mutableStateOf<String?>(null) }
    var editContextLimit by rememberSaveable { mutableStateOf(false) }
    var settings by rememberSaveable { mutableStateOf(false) }
    var configure by rememberSaveable { mutableStateOf(false) }
    var find by key(current.id) { rememberSaveable { mutableStateOf(ConversationFind()) } }
    var drawerHit by remember { mutableStateOf<DrawerHit?>(null) }
    LaunchedEffect(current.id, drawerHit) {
        val hit = drawerHit ?: return@LaunchedEffect
        if (hit.id == current.id) {
            find = find.openHit(current, hit)
            drawerHit = null
        } else if (vm.state.value.current.id != hit.id) {
            drawerHit = null
        }
    }
    val hits = remember(find.query, current.messages) { matchingMessages(state, find.query) }
    val index = find.matchIndex(hits)
    var renameId by rememberSaveable { mutableStateOf<String?>(null) }
    var deleteId by rememberSaveable { mutableStateOf<String?>(null) }
    val sendScope = rememberCoroutineScope()
    val focus = LocalFocusManager.current
    val timelines = rememberSaveableStateHolder()
    val drawerActions = remember(vm, timelines) {
        DrawerActions(vm::hasDrawerDraft, vm::setPinned, vm::moveConversations, vm::duplicateConversation,
            delete = { ids -> ids.forEach(timelines::removeState); vm.deleteConversations(ids) },
            vm::copyAssistant, vm::groupAssistant, vm::canRemoveAssistant, vm::removeEmptyAssistant, vm::setArchived)
    }
    val snackbar = remember { SnackbarHostState() }
    val notice by vm.notice.collectAsStateWithLifecycle()
    val errors by vm.errors.collectAsStateWithLifecycle()
    LaunchedEffect(notice) {
        notice?.let { snackbar.showSnackbar(it); vm.clearNotice() }
    }

    fun closeDrawer() { drawerScope.launch { drawer.close() } }
    fun leaveDrawer(action: () -> Unit) {
        focus.clearFocus()
        // Switching modes can change the sheet width and replace its active drag anchors.
        drawerScope.launch {
            drawer.close()
            action()
        }
    }
    fun newChat() {
        focus.clearFocus()
        vm.newConversation()
    }
    val drawerOpening = drawer.isOpen || (current.mode == Mode.Chat && drawer.targetValue == DrawerValue.Open)
    val contextCovered = drawerOpening || settings || toolsActive || chatPicker.page != null || editVisit != null ||
        assistantEditor != null || renameId != null || deleteId != null || configure
    LaunchedEffect(contextWindow, contextCovered) { if (contextCovered) contextWindow.open = false }
    BackHandler(enabled = !settings && (drawerOpening || find.open)) {
        if (drawerOpening) closeDrawer() else find = ConversationFind()
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
                    allHistory = { state.history }, drawerVisible = visible,
                    assistants = state.assistants, assistantId = current.assistantId, assistantsReady = load == WorkspaceLoad.Ready,
                    onSelectAssistant = { id -> leaveDrawer { vm.selectAssistant(id) } },
                    onRenameAssistant = vm::renameAssistant, currentId = current.id, onNew = { leaveDrawer(vm::newConversation) },
                    onOpen = { id -> leaveDrawer { vm.openConversation(id) } },
                    onSettings = { closeDrawer(); settings = true }, onAssistantSettings = { assistantEditor = it },
                    onRename = { renameId = it }, onPin = vm::togglePin, onDelete = { deleteId = it }, actions = drawerActions,
                    onHit = { hit -> leaveDrawer {
                        vm.openConversation(hit.id)
                        drawerHit = hit.takeIf { it.id == vm.state.value.current.id }
                    } }
                )
            }
        ) {
            val composerCatalog = catalog
            val selectedEntry = remember(composerCatalog, current.mode) { composerCatalog.selectedEntry(current.mode) }
            val assistant = state.assistants.find { it.id == current.assistantId }
            val contextMessages = vm.contextMessages(current)
            val reading = remember(contextMessages, current.mode, current.contextStart, current.modelRoutes, selectedEntry, assistant?.contextLimit) {
                contextUsage(current, selectedEntry, assistant?.contextLimit, contextMessages)
            }
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
                    .contextWindowInput(contextWindow),
                contentAlignment = Alignment.TopCenter
            ) {
                val covered = toolsActive || modelBrowse.active
                ChatComposerLayers(
                    modifier = Modifier.fillMaxSize(),
                    freezeTimelineDock = covered, dockKey = chatPicker,
                    trackPickerAnchor = { editVisit == null && (chatPicker.page != null || modelBrowse.active || chatPicker.drawingBubble) },
                    picker = if (current.mode == Mode.Chat && !toolsActive) {
                        { ChatModelPicker(providers, chatPicker, chatOverride = current.modelRoutes?.get(Mode.Chat), suspended = editVisit != null, hideForReturn = returning,
                            modelBrowseProgress = { modelBrowse.progress }, modifier = Modifier.fillMaxSize().windowInsetsPadding(hostImeInsets)) }
                    } else null,
                    composer = {
                        val bottomBars = WindowInsets.safeDrawing
                        Column(Modifier.windowInsetsPadding(hostImeInsets).windowInsetsPadding(bottomBars.only(WindowInsetsSides.Bottom))) {
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
                                    panelContent = when {
                                        toolsPage != null && toolsActive -> ({
                                            ChatToolsPage(state.draft.search, state.draft.imageTool, vm::setSearch, vm::setImageTool, vm,
                                                selectedEntry?.second?.chatProtocol() == null,
                                                modifier = Modifier.testTag("chat-tools-surface"), onBack = toolsPage::close)
                                        })
                                        modelBrowse.active -> ({
                                            ChatModelBrowsePage(providers, chatPicker, chatOverride = current.modelRoutes?.get(Mode.Chat),
                                                suspended = editVisit != null, hideForReturn = returning, settled = modelBrowse.interactive,
                                                onEditModel = edit@{ route, browse ->
                                                    val origin = vm.state.value
                                                    if (editVisit != null || origin.current.id != current.id) return@edit
                                                    val visibleBottom = view.height - ime.getBottom(density)
                                                    val dockTravel = (visibleBottom - (chatPicker.composerBounds?.top ?: visibleBottom)).coerceAtLeast(0)
                                                    val editor = composerBridge.captureAndDismiss() ?: return@edit
                                                    editVisit = ModelEditVisit(route, current.id, origin.draft, browse, editor, dockTravel.toFloat())
                                                    editTransition.targetState = true
                                                })
                                        })
                                        else -> null
                                    },
                                    allowImeReturn = !blockEditorFocus && !drawerOpening && !configure && !find.open &&
                                        renameId == null && deleteId == null && assistantEditor == null,
                                    onConfigure = { focus.clearFocus(); configure = true },
                                    onSend = { fromDictation ->
                                        // Dictation has already written the final transcript to the live draft.
                                        val submitted = vm.state.value
                                        if (submitted.current.id == current.id && submitted.current.mode == current.mode) {
                                            sendScope.launch {
                                                sendDraft(vm, providers, submitted, composerCatalog, fromDictation)
                                                    ?.let { vm.reportError(current.id, it) }
                                            }
                                        }
                                    })
                            }
                        }
                    },
                ) { timelineDock ->
                    Scaffold(
                        modifier = Modifier.fillMaxSize().behindToolsPage(covered),
                        contentWindowInsets = WindowInsets(0, 0, 0, 0),
                        topBar = {
                            Column {
                                Box(Modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top))) {
                                    Header(
                                        mode = current.mode, locked = state.modeLocked, title = current.title, pinned = current.pinned,
                                        contextUsage = reading, contextWindow = contextWindow,
                                        environmentName = if (state.modeLocked && current.mode == Mode.Chat && current.environmentId != null)
                                            key(vm.environments.revision.collectAsStateWithLifecycle().value) {
                                                conversationEnvironmentName(current, vm.environments::getEnvironment)
                                            } else null,
                                        onMode = vm::setMode, onNew = ::newChat,
                                        onDrawer = { focus.clearFocus(); drawerScope.launch { drawer.open() } },
                                        onRename = { renameId = current.id }, onPin = { vm.togglePin(current.id) },
                                        onFind = { find = find.copy(open = true) }, onDelete = { deleteId = current.id }
                                    )
                                }
                                if (find.open) FindBar(find.query, { find = find.copy(query = it, messageId = null) }, index, hits.size,
                                    previous = {
                                        if (hits.isNotEmpty()) find = find.copy(messageId = hits[if (index <= 0) hits.lastIndex else index - 1])
                                    },
                                    next = {
                                        if (hits.isNotEmpty()) find = find.copy(messageId = hits[(index + 1) % hits.size])
                                    },
                                    onClose = { find = ConversationFind(); focus.clearFocus() })
                            }
                        },
                        bottomBar = { Spacer(Modifier.height(timelineDock)) },
                        snackbarHost = { SnackbarHost(snackbar) }
                    ) { bars ->
                        if (current.messages.isNotEmpty()) {
                            timelines.SaveableStateProvider(current.id) {
                                MessageList(vm, state, find.query, hits.getOrNull(index), find.jump, floatingBars = bars,
                                    composerBoundsInWindow = { chatPicker.composerBounds.takeUnless { covered } })
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
                    if (current.mode == Mode.Chat && state.modeLocked && !contextCovered) {
                        ContextWindowOverlay(contextWindow, reading,
                            onManage = if (assistant != null && load == WorkspaceLoad.Ready) ({
                                contextWindow.open = false
                                editContextLimit = true
                                assistantEditor = assistant.id
                            }) else null,
                            modifier = Modifier.fillMaxSize().padding(bottom = timelineDock))
                    }
                    if (covered) {
                        val label = stringResource(R.string.chat_tools_back)
                        Spacer(Modifier.fillMaxSize().drawBehind {
                            val p = if (toolsActive) toolsPage?.progress ?: 0f else modelBrowse.progress
                            drawRect(Color.Black.copy(alpha = .45f * p))
                        }.testTag("composer-panel-dismiss").clickable(
                            interactionSource = remember { MutableInteractionSource() }, indication = null, onClickLabel = label
                        ) { if (toolsActive) toolsPage?.close() else chatPicker.dismiss() })
                    }
                }
            }
        }
    }

    editVisit?.let { visit ->
        var earlyBack by remember(visit) { mutableStateOf(false) }
        fun startReturn() {
            keyboard?.hide()
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
                composerBridge.restore(visit.editor.copy(imeVisible = false, focusedInput = null))
                snapshotFlow { editTransition.isIdle && chatPicker.pendingBrowseRestore == null }.first { it }
            } else snapshotFlow { editTransition.isIdle }.first { it }
            editVisit = null
        }
    }

    if (configure) ConfigureSheet(providers, current.mode) { configure = false }
    fun titleFor(id: String) = if (id == current.id) current.title else state.history.firstOrNull { it.id == id }?.title.orEmpty()
    assistantEditor?.let { id ->
        io.hydrion.hub.ui.settings.AssistantDetail(vm, id, openAtContextLimit = editContextLimit) {
            assistantEditor = null
            editContextLimit = false
        }
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
        history.asSequence().filter { it.mode == Mode.Image && !it.archived }.take(3).toList()
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
