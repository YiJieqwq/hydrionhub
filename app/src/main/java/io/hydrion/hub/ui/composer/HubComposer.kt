package io.hydrion.hub.ui.composer

import android.content.res.Configuration
import android.provider.Settings
import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imeAnimationSource
import androidx.compose.foundation.layout.imeAnimationTarget
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ripple
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.key
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.onImeAction
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.round
import androidx.compose.ui.unit.roundToIntRect
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import androidx.compose.ui.unit.sp
import io.hydrion.hub.R
import io.hydrion.hub.provider.ChatTriggerLabel
import io.hydrion.hub.provider.ChatTriggerText
import io.hydrion.hub.ui.pickerModelLabelBounds
import io.hydrion.hub.provider.ChatModelPickerState
import io.hydrion.hub.provider.pickerBubbleHighlight
import io.hydrion.hub.provider.pickerComposerMaxHeight
import io.hydrion.hub.provider.chatModelPickerPreImeBack
import io.hydrion.hub.model.Attachment
import io.hydrion.hub.model.HubState
import io.hydrion.hub.model.HubViewModel
import io.hydrion.hub.model.Mode
import io.hydrion.hub.ui.searchLineHeightLines
import io.hydrion.hub.ui.pickerCapsuleBounds
import io.hydrion.hub.ui.messages.BitmapPreview
import io.hydrion.hub.ui.messages.PicturePreview
import io.hydrion.hub.ui.messages.rememberAttachmentPreview
import io.hydrion.hub.ui.messages.rememberGeneratedPreview
import io.hydrion.hub.ui.theme.HubStyle
import io.hydrion.hub.ui.theme.capsuleEdge
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlin.math.roundToInt

private enum class ToolsPanel { Closed, WaitingForIme, Open }

private const val ChatDraftHint = "Chat with AI"
private val FoldedTextStart = 48.dp
private val OpenTextStart = 15.dp
private val OpenTextEnd = 16.dp
private val FoldedTextTop = 5.dp
private val FoldedEditorMinHeight = 49.25.dp
private val OpenTextTop = 16.dp
private val TextBottom = 4.dp

private val ComposerSecondary = Color(0xFFAFAFAF)
private val ComposerGlyphSize = 18.dp
private val ComposerGlyphStroke = 2.dp
private val ComposerGlyphFineStroke = 1.5.dp
private val ToolbarGlyphGapReduction = 3.dp

internal data class ComposerMotionInput(
    val height: Int, val source: Int, val target: Int, val editing: Boolean, val hardwareOnly: Boolean,
    val imeVisible: Boolean = true,
)

@Composable
internal fun rememberComposerShape(input: () -> ComposerMotionInput): State<Float> {
    val readInput by rememberUpdatedState(input)
    val progress = remember { Animatable(0f) }
    LaunchedEffect(progress) {
        val motion = ComposerMotion()
        snapshotFlow { readInput() }.map { sample ->
            // Read the rendered value outside snapshotFlow so tween frames don't retrigger the input.
            motion.update(
                sample.height, sample.source, sample.target, sample.editing, sample.hardwareOnly,
                progress.value, sample.imeVisible,
            )
        }.distinctUntilChanged().collectLatest { goal ->
            when (goal) {
                is ComposerMorph.Tween -> progress.animateTo(goal.end, tween(goal.millis, easing = LinearEasing))
                is ComposerMorph.At -> progress.snapTo(goal.fraction)
                is ComposerMorph.FollowAfterBlur -> {
                    progress.snapTo(goal.fraction)
                    // collectLatest cancels the fallback when travel or the editing direction changes.
                    delay(ComposerMorph.FollowAfterBlur.IdleTimeoutMillis)
                    progress.animateTo(0f, tween(ComposerMorph.Tween.LoseFocus.millis, easing = LinearEasing))
                }
            }
        }
    }
    return progress.asState()
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun HubComposer(
    vm: HubViewModel,
    state: HubState,
    modelLabel: ChatTriggerLabel,
    modelAvailable: Boolean,
    fast: Boolean,
    onConfigure: () -> Unit,
    onSend: (fromDictation: Boolean) -> Unit,
    modifier: Modifier = Modifier,
    toolsAvailable: Boolean = true,
    allowImeReturn: Boolean = true,
    chatPicker: ChatModelPickerState? = null,
    modelEditorBridge: ModelEditorComposerBridge? = null,
    forceReturnFolded: Boolean = false,
    chatTools: ToolsPageState? = null,
    modelBrowseProgress: () -> Float = { 0f },
) {
    val colors = MaterialTheme.colorScheme
    val darkComposer = colors.onSurface.luminance() > colors.surfaceContainerHigh.luminance()
    val glyphAccent = if (darkComposer) Color.White else colors.onSurface
    val glyphLine = if (darkComposer) Color.White else colors.onSurfaceVariant
    val chat = state.current.mode == Mode.Chat
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val density = LocalDensity.current
    val windowFocused = LocalWindowInfo.current.isWindowFocused
    val imeVisible = WindowInsets.isImeVisible
    val nav = WindowInsets.navigationBars.getBottom(density)
    val current = WindowInsets.ime
    val source = WindowInsets.imeAnimationSource
    val target = WindowInsets.imeAnimationTarget
    val hasImeHeight by remember(current, density, nav) { derivedStateOf { current.getBottom(density) > nav } }
    val hasImeTarget by remember(target, density, nav) { derivedStateOf { target.getBottom(density) > nav } }
    val hiding by remember(source, target, density, nav) {
        derivedStateOf { source.getBottom(density) > nav && target.getBottom(density) <= nav }
    }
    val opening by remember(source, target, density, nav) {
        derivedStateOf { target.getBottom(density) > maxOf(nav, source.getBottom(density)) }
    }
    val tools = chatTools?.takeIf { state.current.mode == Mode.Chat }
    val toolEditor = tools?.takeIf { it.active }
    val browseProgress = if (chatPicker != null) modelBrowseProgress() else 0f
    val browsing = chatPicker?.modelListNavigation == true
    // Keep both endpoints unavailable until the reverse flight has landed.
    val modelSearch = chatPicker?.takeIf { browsing || browseProgress > 0f }
    val editingQuery = toolEditor != null || modelSearch != null
    var lastModelQuery by remember(chatPicker) { mutableStateOf(TextFieldValue()) }
    val modelQuery = if (browsing) chatPicker!!.searchQuery else lastModelQuery
    SideEffect {
        if (browsing) lastModelQuery = modelQuery.copy(composition = null)
        else if (modelSearch == null && lastModelQuery.text.isNotEmpty()) lastModelQuery = TextFieldValue()
    }
    val pageProgress = if (modelSearch != null) browseProgress else tools?.progress ?: 0f
    // A pending background restore belongs to its editing role, not a later tool query or draft.
    val imeReturn = remember(state.current.id, state.current.mode, toolEditor, modelSearch) { ComposerImeReturn() }
    val editorFocus = remember { FocusRequester() }
    val draft = state.draft
    // An unsent conversation shares its draft and insertion point between Chat and Image.
    var fieldValue by remember(state.current.id) {
        mutableStateOf(TextFieldValue(draft.text))
    }
    val editorValue = if (fieldValue.text == draft.text) fieldValue else fieldValue.copy(text = draft.text, composition = null)
    var focused by remember { mutableStateOf(false) }
    var sawKeyboard by remember { mutableStateOf(false) }
    var sawHideAnimation by remember { mutableStateOf(false) }
    var toolsPanel by remember(state.current.id, state.current.mode) { mutableStateOf(ToolsPanel.Closed) }
    val toolsRequested = toolsPanel != ToolsPanel.Closed
    val canOpenTools = toolsAvailable && (tools != null || state.current.mode == Mode.Image)
    fun cancelPendingTools() {
        if (toolsPanel == ToolsPanel.WaitingForIme) toolsPanel = ToolsPanel.Closed
    }
    var importError by remember(state.current.id) { mutableStateOf<String?>(null) }
    val launchPicker = rememberAttachmentPicker(vm) { importError = it }
    fun pick() {
        cancelPendingTools()
        imeReturn.cancel()
        launchPicker()
    }

    fun collapse() {
        cancelPendingTools()
        imeReturn.cancel()
        focusManager.clearFocus()
        keyboard?.hide()
    }
    val imeActive = imeVisible || hasImeHeight || hasImeTarget
    val hiddenAfterEditing = sawKeyboard && !imeActive
    val configuration = LocalConfiguration.current
    val hardwareKeyboard = configuration.keyboard == Configuration.KEYBOARD_QWERTY &&
        configuration.hardKeyboardHidden == Configuration.HARDKEYBOARDHIDDEN_NO
    val resolver = LocalContext.current.contentResolver
    val hardwareKeyboardOnly = remember(resolver, hardwareKeyboard, windowFocused) {
        hardwareKeyboard && Settings.Secure.getInt(resolver, "show_ime_with_hard_keyboard", 0) == 0
    }
    val pickerOpen = chatPicker?.page != null
    val hardwareOnly = hardwareKeyboardOnly && !pickerOpen && !hiddenAfterEditing && !sawHideAnimation
    SideEffect {
        if (fieldValue.text != draft.text) fieldValue = editorValue
        // Composing spans belong to the outgoing IME session; keep the draft text and selection.
        if (editingQuery && fieldValue.composition != null) fieldValue = fieldValue.copy(composition = null)
        if (focused && hasImeHeight) sawKeyboard = true
        if (hiding && hasImeHeight) sawHideAnimation = true
    }
    val editing = focused && !toolsRequested && !hiddenAfterEditing && (windowFocused || (sawKeyboard && imeActive))
    // A delayed click or text callback must still belong to this draft-editing session.
    val draftSession = remember(imeReturn, modelSearch, toolEditor) { mutableStateOf(true) }
    DisposableEffect(draftSession) { onDispose { draftSession.value = false } }
    fun acceptsDraftInput(): Boolean {
        val conversation = vm.state.value.current
        return draftSession.value && !editingQuery && tools?.active != true && conversation.id == state.current.id &&
            conversation.mode == state.current.mode && chatPicker?.modelListNavigation != true
    }
    fun updateDraft(updated: TextFieldValue) {
        fieldValue = updated
        if (updated.text != vm.state.value.draft.text) {
            vm.setDraft(updated.text)
            chatPicker?.dismissEffort()
        }
    }
    // Attachments and tool settings belong to the same unsubmitted message as the recording.
    val dictation = key(state.current.id, chat, draft.attachments, draft.search, draft.imageTool) {
        rememberChatDictation(
            conversationId = state.current.id,
            enabled = chat && allowImeReturn && !forceReturnFolded && !editingQuery && !toolsRequested,
            readDraft = {
                fieldValue.takeIf { acceptsDraftInput() && vm.state.value.draft == draft && it.text == draft.text }
            },
            onResult = { updated, send ->
                if (acceptsDraftInput() && vm.state.value.draft == draft) {
                    updateDraft(updated)
                    if (send && modelAvailable && !vm.state.value.isGenerating) onSend(true)
                }
            },
        )
    }
    val dictating = dictation.isActive
    ComposerImeReturnEffect(
        controller = imeReturn,
        enabled = allowImeReturn && !toolsRequested && !hardwareKeyboardOnly && !editingQuery,
        editorFocused = focused, windowFocused = windowFocused && !dictation.prompting,
        imeShown = imeVisible || hasImeHeight, dismissing = hiding, opening = opening,
        clearAfterDismissal = hiddenAfterEditing && !toolsRequested && !editingQuery,
        selection = ComposerEditorSelection(editorValue.text, editorValue.selection.start, editorValue.selection.end),
        onRestore = {
            sawKeyboard = hasImeHeight
            sawHideAnimation = false
            if (!focused) editorFocus.requestFocus()
            imeReturn.selectionFor(editorValue.text)?.let { range ->
                val saved = TextRange(range.start, range.end)
                if (editorValue.selection != saved) {
                    fieldValue = editorValue.copy(selection = saved, composition = null)
                }
            }
            keyboard?.show()
        },
        onDismissed = { focusManager.clearFocus() },
    )
    if (toolsPanel == ToolsPanel.WaitingForIme) {
        AwaitToolsImeDismissal(imeActive, windowFocused && allowImeReturn && toolsAvailable && !pickerOpen) { present ->
            if (toolsPanel == ToolsPanel.WaitingForIme) toolsPanel = if (present) ToolsPanel.Open else ToolsPanel.Closed
        }
    }
    BackHandler(enabled = dictating || toolsPanel == ToolsPanel.WaitingForIme || (focused && !toolsRequested && !pickerOpen && !editingQuery)) {
        when {
            dictating -> dictation.cancel()
            toolsPanel == ToolsPanel.WaitingForIme -> cancelPendingTools()
            else -> collapse()
        }
    }
    val shapeProgress = rememberComposerShape {
        ComposerMotionInput(
            (current.getBottom(density) - nav).coerceAtLeast(0),
            (source.getBottom(density) - nav).coerceAtLeast(0),
            (target.getBottom(density) - nav).coerceAtLeast(0),
            editing, hardwareOnly, imeVisible,
        )
    }
    // Legacy keyboards may jump straight to zero before the flow collector sees the final inset.
    val expansion = if (!forceReturnFolded && (hasImeHeight || hasImeTarget || hardwareOnly)) shapeProgress.value else 0f
    if (chatPicker != null) {
        // The floating panel belongs to this composer even while its toolbar trigger is folded away.
        DisposableEffect(chatPicker) {
            onDispose {
                chatPicker.editorBounds = null
                chatPicker.editorTextBounds = null
                chatPicker.dismiss()
            }
        }
    }
    val hasContent = draft.text.isNotEmpty() || draft.attachments.isNotEmpty()
    // The underlying draft retains its ordinary IME/draft shape. Only the page's shared
    // progress drives the transformation into the one-line search capsule.
    val rowGate = rememberDraftRowMotion(hasContent, forceReturnFolded).fraction
    val chatToolbar = if (forceReturnFolded) 0f else maxOf(expansion, rowGate)
    // A keyboard may hide halfway through the panel flight. Continue measuring its source label
    // until landing; when opening from an already folded composer the picker uses its fallback.
    var hadModelRow by remember(chatPicker) { mutableStateOf(false) }
    SideEffect {
        if (modelSearch == null || chatToolbar > 0f) hadModelRow = chatToolbar > 0f
    }
    val toolbar = chatToolbar * (1f - pageProgress)
    val returning = modelEditorBridge?.returning != null
    val overlay: Any? = if (chatPicker == null || forceReturnFolded || returning || !allowImeReturn) null
        else chatPicker.page ?: tools?.takeIf { it.targetOpen }
    val effortAtEditor = overlay == ChatModelPickerState.Page.Controls && !editingQuery
    val editorTap = Modifier.dismissOnEditorTap(
        owner = overlay,
        onSingleTap = if (effortAtEditor) ({ if (acceptsDraftInput()) chatPicker?.dismissEffort() }) else null,
        onFirstDown = {
            val liveText = vm.state.value.draft.text
            if (fieldValue.text == liveText) fieldValue else fieldValue.copy(text = liveText, composition = null)
        },
        onDoubleTap = { original ->
            if (vm.state.value.current.id == state.current.id && (chatPicker?.page != null || tools?.targetOpen == true)) {
                if (original.text == vm.state.value.draft.text && original.text == fieldValue.text) {
                    fieldValue = fieldValue.copy(selection = original.selection)
                }
                chatPicker?.dismiss()
                tools?.close()
            }
        },
    )
    val returnFrame = remember(modelEditorBridge) { mutableStateOf<ModelEditorReturnFrame?>(null) }
    if (modelEditorBridge != null) {
        ObserveModelEditorReturn(modelEditorBridge, returnFrame.value)
        DisposableEffect(modelEditorBridge) { onDispose { modelEditorBridge.hasLayout = false } }
    }
    val canSend = (modelAvailable || state.current.mode == Mode.Image) &&
        (draft.text.isNotBlank() || draft.attachments.isNotEmpty())
    val active = state.isGenerating
    val onAction: () -> Unit = {
        if (acceptsDraftInput() && vm.state.value.isGenerating == active) {
            if (active) vm.stop() else if (canSend) {
                collapse()
                onSend(false)
            }
        }
    }
    val sideInset = lerp(35.dp, 12.25.dp, expansion)
    // The host has already consumed max(IME, navigation bars). On gesture navigation
    // the collapsed reference is measured from the physical edge, not the nav top.
    val compactGap = (35.dp - with(density) { nav.toDp() }).coerceAtLeast(8.dp)
    val bottomGap = lerp(compactGap, 12.25.dp, expansion)
    val editorMinHeight = lerp(FoldedEditorMinHeight, 46.5.dp, toolbar)
    val controlRowHeight = 47.75.dp
    val editorTextStyle = MaterialTheme.typography.bodyLarge.copy(fontSize = 16.sp)
    val textTop = lerp(FoldedTextTop, OpenTextTop, toolbar)
    var draftOverflowPx by remember(state.current.id, state.current.mode) { mutableIntStateOf(0) }
    val safeArea = WindowInsets.safeDrawing
    val pickerRoom = if (!pickerOpen) Modifier else Modifier.layout { measurable, limits ->
        val keep = (editorMinHeight + controlRowHeight + bottomGap).roundToPx()
        val height = pickerComposerMaxHeight(limits.maxHeight, safeArea.getTop(this), keep)
        val composer = measurable.measure(limits.copy(maxHeight = height.coerceAtLeast(limits.minHeight)))
        layout(composer.width, composer.height) { composer.place(0, 0) }
    }
    Column(modifier.then(pickerRoom).fillMaxWidth().padding(start = sideInset, end = sideInset, bottom = bottomGap)) {
        importError?.takeIf { !editingQuery }?.let { error ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(error, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = colors.error)
                IconButton(onClick = { importError = null }) {
                    Icon(Icons.Outlined.Close, "关闭附件提示", Modifier.size(16.dp))
                }
            }
        }
        val shape = if (dictating || toolbar == 0f) CircleShape else RoundedCornerShape(24.dp)
        Surface(
            modifier = Modifier.fillMaxWidth().testTag("composer-capsule")
                .then(if (chatPicker != null) Modifier.pickerCapsuleBounds() else Modifier)
                .then(if (modelEditorBridge == null) Modifier else Modifier.layout { measurable, constraints ->
                    val capsule = measurable.measure(constraints)
                    layout(capsule.width, capsule.height) {
                        capsule.place(0, 0)
                        modelEditorBridge.hasLayout = capsule.width > 0 && capsule.height > 0
                        returnFrame.value = if (returning) ModelEditorReturnFrame(
                            capsule.width, capsule.height, (current.getBottom(density) - nav).coerceAtLeast(0),
                            (source.getBottom(density) - nav).coerceAtLeast(0), (target.getBottom(density) - nav).coerceAtLeast(0),
                            imeVisible, expansion, rowGate, hasContent, forceReturnFolded,
                        ) else null
                    }
                })
                .then(if (chatPicker == null) Modifier else Modifier.onGloballyPositioned {
                    chatPicker.composerBounds = IntRect(it.positionInWindow().round(), it.size)
                })
                .border(0.66.dp, HubStyle.ComposerBorder, shape),
            shape = shape,
            color = colors.surfaceContainerHigh,
        ) {
            Column {
                if (!dictating && draft.attachments.isNotEmpty() && !forceReturnFolded && pageProgress < 1f) {
                    val imageAndText = draft.text.isNotEmpty() && draft.attachments.any { it.hasImagePreview }
                    // Let the editor and toolbar measure first in short windows above the IME.
                    BoxWithConstraints(Modifier.weight(1f, fill = false).fillMaxWidth()
                        .then(when {
                            editingQuery -> Modifier.clearAndSetSemantics { }.revealHeight(1f - pageProgress, 0.dp)
                            returning -> Modifier.revealHeight(toolbar, 0.dp)
                            else -> Modifier
                        })
                        .padding(start = 8.dp, end = 8.dp, top = 3.dp, bottom = if (imageAndText) 0.dp else 3.dp)) {
                        val previewWidth = maxWidth
                        val previewHeight = minOf(120.dp, maxHeight)
                        LazyRow(
                            Modifier.fillMaxWidth().testTag("draft-attachments"),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            items(draft.attachments, key = { it.id }) { attachment ->
                                DraftAttachment(attachment, previewWidth, previewHeight) {
                                    if (!editingQuery && tools?.active != true && chatPicker?.modelListNavigation != true) vm.removeAttachment(attachment.id)
                                }
                            }
                        }
                    }
                }
                Box(Modifier.fillMaxWidth()) {
                    // Keep the native input connection alive beneath the one-line voice controls.
                    val editorLayer = if (dictating) Modifier.height(48.dp)
                        .clearAndSetSemantics { }.drawWithContent { } else Modifier
                    Column(editorLayer) {
                        ComposerEditorSlot(
                            searching = browsing, editorFocus = editorFocus, modelEditorBridge = modelEditorBridge,
                            readDraft = { fieldValue },
                            restoreSelection = { fieldValue = fieldValue.copy(selection = it, composition = null) },
                            interactive = allowImeReturn,
                            dismissForModelEditor = ::collapse,
                            onEditorFocusChanged = { nowFocused ->
                                if (nowFocused && !focused) {
                                    sawKeyboard = false
                                    sawHideAnimation = false
                                }
                                focused = nowFocused
                            },
                            modifier = (if (pickerOpen) Modifier.weight(1f, fill = false) else Modifier)
                                .then(if (returning || forceReturnFolded) Modifier.revealHeight(toolbar, FoldedEditorMinHeight) else Modifier),
                        ) { draftFocusModifier ->
                            val modelHint = stringResource(R.string.chat_picker_search_hint)
                            val toolHint = stringResource(R.string.tools_search_hint)
                            val visibleModelQuery = modelSearch != null && !forceReturnFolded
                            val shown = if (visibleModelQuery) modelQuery else toolEditor?.query ?: editorValue
                            val showingChatDraft = chat && !visibleModelQuery && toolEditor == null
                            val inputHint = if (visibleModelQuery) modelHint else if (toolEditor != null) toolHint
                                else if (state.current.mode == Mode.Chat) ChatDraftHint else "描述想生成的图片"
                            BasicTextField(
                                // The same field, FocusRequester and native IME options serve both roles.
                                value = shown,
                                onValueChange = { updated ->
                                    if (modelSearch != null) {
                                        if (draftSession.value && modelSearch.modelListNavigation && allowImeReturn) {
                                            // Some IMEs commit a literal newline for Enter with the draft's native options.
                                            if (isQuerySubmitEdit(modelSearch.searchQuery, updated)) modelSearch.submitSearch()
                                            else modelSearch.updateSearchQuery(updated.singleLineQuery())
                                        }
                                    } else if (toolEditor != null) {
                                        if (draftSession.value && toolEditor.targetOpen) {
                                            toolEditor.query = updated.singleLineQuery()
                                        }
                                    } else if (!dictation.isActive && acceptsDraftInput()) {
                                        updateDraft(updated)
                                    }
                                },
                                modifier = Modifier.fillMaxWidth().padding(
                                    start = lerp(FoldedTextStart, OpenTextStart, toolbar), end = lerp(FoldedTextStart, OpenTextEnd, toolbar)
                                ).then(if (!editingQuery) Modifier else Modifier
                                    .testTag(if (modelSearch != null) "chat-model-search-field" else "tools-search-field")
                                    .onPreviewKeyEvent { event ->
                                        val key = event.nativeKeyEvent
                                        if (key.keyCode != KeyEvent.KEYCODE_ENTER && key.keyCode != KeyEvent.KEYCODE_NUMPAD_ENTER) false
                                        else {
                                            if (key.action == KeyEvent.ACTION_UP && !key.isCanceled) modelSearch?.submitSearch()
                                            true
                                        }
                                    })
                                    .chatModelPickerPreImeBack((pickerOpen && chatPicker?.modelListNavigation == false) || toolEditor != null) {
                                        if (toolEditor != null) toolEditor.close() else chatPicker?.systemBack()
                                    }
                                    .then(draftFocusModifier)
                                    .semantics {
                                        contentDescription = if (editingQuery) inputHint else "消息输入框"
                                        if (effortAtEditor) onClick {
                                            if (!acceptsDraftInput()) return@onClick false
                                            chatPicker?.dismissEffort()
                                            if (!focused) editorFocus.requestFocus()
                                            if (!imeActive && !hardwareKeyboardOnly) keyboard?.show()
                                            true
                                        }
                                        if (visibleModelQuery) onImeAction(ImeAction.Search) {
                                            modelSearch?.submitSearch()
                                            true
                                        }
                                    },
                                textStyle = editorTextStyle.copy(
                                    color = colors.onSurface,
                                    letterSpacing = if (showingChatDraft) 0.1.sp else editorTextStyle.letterSpacing,
                                ),
                                cursorBrush = SolidColor(colors.primary),
                                maxLines = if (!editingQuery && !forceReturnFolded && (hasContent || focused)) 5 else 1,
                                keyboardActions = if (editingQuery) KeyboardActions(onSearch = { modelSearch?.submitSearch() },
                                    onDone = { modelSearch?.submitSearch() }, onSend = { modelSearch?.submitSearch() })
                                    else KeyboardActions.Default,
                                decorationBox = { editor ->
                                    Box(Modifier.fillMaxWidth()
                                        .trackPickerEditor(chatPicker)
                                        .then(if (effortAtEditor) editorTap else Modifier)
                                        .layout { content, limits ->
                                            val field = content.measure(limits)
                                            // Switching to a blank query must not drop the old draft's wrapped lines in one frame.
                                            if (!editingQuery) draftOverflowPx = (field.height - editorMinHeight.roundToPx()).coerceAtLeast(0)
                                            val extra = if (!editingQuery) 0 else (draftOverflowPx * (1f - pageProgress)).roundToInt()
                                            // Use the measured query line, without the animated draft overflow or top
                                            // padding, to reserve its exact landing space for the scrolling page.
                                            val lines = if (editingQuery) searchLineHeightLines(maxOf(
                                                FoldedEditorMinHeight.roundToPx(),
                                                field.height - textTop.roundToPx() + FoldedTextTop.roundToPx(),
                                            )) else emptyMap()
                                            layout(field.width, limits.constrainHeight(field.height + extra), lines) { field.place(0, 0) }
                                        }.heightIn(min = editorMinHeight)
                                        .padding(top = textTop, bottom = TextBottom),
                                        contentAlignment = Alignment.CenterStart) {
                                        val visibleTextLine = if (!forceReturnFolded && chatPicker != null)
                                            Modifier.testTag("composer-editable-text").then(if (effortAtEditor) Modifier else editorTap)
                                        else Modifier
                                        Box(Modifier.fillMaxWidth().trackPickerEditor(chatPicker, textOnly = true)
                                            .then(visibleTextLine), contentAlignment = Alignment.CenterStart) {
                                            if (shown.text.isEmpty()) {
                                                val isChat = state.current.mode == Mode.Chat
                                                Text(
                                                    inputHint,
                                                    color = HubStyle.ComposerHint,
                                                    style = if (isChat) editorTextStyle else editorTextStyle.copy(fontSize = 17.sp),
                                                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                                                )
                                            }
                                            editor()
                                        }
                                    }
                                },
                            )
                        }
                        Spacer(Modifier.height(controlRowHeight * toolbar))
                    }
                    if (dictating) {
                        DictationBar(
                            level = { dictation.level },
                            onCancel = dictation::cancel, onSave = dictation::save, onSend = dictation::send,
                            canSend = modelAvailable && !state.isGenerating, waiting = dictation.waiting,
                            resultReady = dictation.resultReady,
                            // Hit test even the silent space between actions, above the live editor.
                            modifier = Modifier.fillMaxWidth().height(48.dp).pointerInput(Unit) {
                                awaitPointerEventScope { while (true) awaitPointerEvent() }
                            },
                        )
                    } else ToolsToolbarMorph(
                        progress = pageProgress, toolsOpen = browsing || tools?.targetOpen == true, modelSearch = modelSearch != null,
                        hasClearableTools = (modelSearch?.searchQuery ?: toolEditor?.query)?.text?.isNotEmpty() == true,
                        generating = active, canSend = canSend,
                        onAttach = { if (acceptsDraftInput()) pick() }, onSend = onAction,
                        onClear = { if (modelSearch != null) modelSearch.clearSearchQuery() else toolEditor?.clearQuery() },
                        onBack = { if (modelSearch?.modelListNavigation == true) modelSearch.open() else toolEditor?.close() },
                        modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                            .height(lerp(editorMinHeight, controlRowHeight, toolbar)).padding(horizontal = 3.dp),
                    ) { leading, trailing ->
                        if ((chatToolbar > 0f || modelSearch != null && hadModelRow) && (pageProgress < 1f || modelSearch != null)) {
                            val interactive = chatToolbar >= 0.6f && !editingQuery
                            val reveal = Modifier.graphicsLayer {
                                alpha = chatToolbar
                                translationX = -8.dp.toPx() * (1f - chatToolbar)
                            }.then(if (interactive) Modifier else Modifier.clearAndSetSemantics { })
                            Row(leading.then(reveal), verticalAlignment = Alignment.CenterVertically) {
                                ComposerIconButton("打开工具箱", onClick = {
                                    if (tools != null) {
                                        if (toolsAvailable && !tools.active) {
                                            imeReturn.cancel()
                                            chatPicker?.dismiss()
                                            tools.open()
                                        }
                                    } else if (state.current.mode == Mode.Image) {
                                        if (toolsPanel == ToolsPanel.WaitingForIme) cancelPendingTools()
                                        else {
                                            imeReturn.cancel()
                                            toolsPanel = if (imeActive) ToolsPanel.WaitingForIme else ToolsPanel.Open
                                            if (imeActive) keyboard?.hide()
                                        }
                                    }
                                }, enabled = interactive && canOpenTools) {
                                    val glyph = Modifier.offset(x = -ToolbarGlyphGapReduction)
                                    if (chat) {
                                        Icon(
                                            painterResource(R.drawable.ic_chat_toolbox), contentDescription = null,
                                            modifier = glyph.size(ComposerGlyphSize), tint = ChatToolInk,
                                        )
                                    } else {
                                        val track = if (darkComposer && !toolsAvailable) ComposerSecondary else glyphLine
                                        ToolGlyph(track, if (toolsAvailable) glyphAccent else track, glyph)
                                    }
                                }
                                if (!chat) {
                                    Box(Modifier.width(ToolbarActionSlot).height(48.dp).clearAndSetSemantics {
                                        contentDescription = "工作区终端"
                                        role = Role.Button
                                        disabled()
                                    }, contentAlignment = Alignment.Center) {
                                        TerminalGlyph(glyphAccent, glyphLine, Modifier.offset(x = -ToolbarGlyphGapReduction * 2))
                                    }
                                }
                            }
                            Box(Modifier.weight(1f).then(reveal), contentAlignment = Alignment.CenterEnd) {
                                val presses = remember { MutableInteractionSource() }
                                val modelText = remember(chatPicker) { ModelLabelMeasurement() }
                                val trigger = if (chatPicker == null) Modifier else {
                                    val bounds = if (!interactive) Modifier else {
                                        DisposableEffect(chatPicker) { onDispose { chatPicker.triggerBounds = null } }
                                        Modifier.onGloballyPositioned {
                                            modelText.trigger = it
                                            chatPicker.triggerBounds = IntRect(it.positionInWindow().round(), it.size)
                                            modelText.record(chatPicker)
                                        }
                                    }
                                    Modifier.testTag("chat-model-trigger").then(bounds)
                                }
                                val labelEndPadding = if (chat) 16.dp else 4.dp
                                val actionLabel = if (chatPicker != null) stringResource(R.string.chat_controls_select_model)
                                    else "配置模型"
                                val closePickerLabel = stringResource(R.string.chat_picker_close)
                                val labelStyle = MaterialTheme.typography.labelLarge
                                val measurer = rememberTextMeasurer()
                                val modelWordGap = if (modelLabel.effort == null) 0 else remember(measurer, labelStyle) {
                                    measurer.measure(" ", labelStyle, maxLines = 1, softWrap = false).size.width
                                }
                                val triggerWidth = if (chatPicker != null) {
                                    val width = remember(measurer, modelLabel, labelStyle, fast, density) {
                                        (25.dp + with(density) { modelLabel.width(measurer, labelStyle, modelWordGap).toDp() } + if (fast) 17.dp else 0.dp)
                                            .coerceAtLeast(44.dp)
                                    }
                                    Modifier.width(width)
                                } else Modifier
                                Row(trailing, verticalAlignment = Alignment.CenterVertically) {
                                    Row(
                                        Modifier.weight(1f, fill = false).then(trigger).then(if (pickerOpen) Modifier else Modifier.clip(CircleShape)).semantics {
                                            contentDescription = if (pickerOpen) closePickerLabel else actionLabel
                                            stateDescription = modelLabel.text
                                        }.clickable(interactionSource = presses,
                                            indication = if (chatPicker == null) LocalIndication.current else null,
                                            enabled = interactive, role = Role.Button) {
                                            cancelPendingTools()
                                            if (pickerOpen) chatPicker?.dismiss()
                                            else if (chatPicker != null) chatPicker.open() else {
                                                collapse()
                                                onConfigure()
                                            }
                                        }.heightIn(min = 44.dp).then(triggerWidth)
                                            .padding(start = 25.dp - labelEndPadding, end = labelEndPadding),
                                        horizontalArrangement = Arrangement.spacedBy(2.dp, Alignment.End),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        if (fast) {
                                            if (pickerOpen) Spacer(Modifier.size(15.dp))
                                            else Icon(Icons.Outlined.Bolt, "快速", Modifier.size(15.dp), tint = colors.onSurface)
                                        }
                                        Box(Modifier.weight(1f, fill = false)) {
                                            if (chatPicker != null) {
                                                // The initial highlight hands its shape and color to the moving shell.
                                                // Keep the open label's press feedback on its measured text.
                                                val feedback = if (pickerOpen) Modifier.indication(presses, ripple(bounded = false))
                                                else {
                                                    val pressed = presses.collectIsPressedAsState()
                                                    Modifier.drawBehind { if (pressed.value) drawRect(colors.pickerBubbleHighlight) }
                                                }
                                                Spacer(Modifier.matchParentSize().clip(CircleShape).then(feedback))
                                            }
                                            val textBounds = if (interactive && chatPicker != null)
                                                Modifier.testTag("chat-model-source-label").onGloballyPositioned {
                                                    modelText.label = it
                                                    modelText.record(chatPicker)
                                                } else Modifier
                                            if (chatPicker != null) ChatTriggerText(modelLabel, darkComposer, labelStyle, modelWordGap,
                                                Modifier.pickerModelLabelBounds().then(textBounds))
                                            else Text(modelLabel.name, style = labelStyle, color = colors.onSurface,
                                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        }
                                    }
                                    if (chat) {
                                        ComposerIconButton(
                                            label = stringResource(R.string.chat_dictate),
                                            onClick = {
                                                chatPicker?.dismissEffort()
                                                dictation.start()
                                                if (dictation.prompting) {
                                                    imeReturn.onSpeechPrompt(ComposerEditorSelection(
                                                        fieldValue.text, fieldValue.selection.start, fieldValue.selection.end))
                                                }
                                            },
                                            enabled = interactive && allowImeReturn && !returning,
                                            modifier = Modifier.padding(end = 5.dp).testTag("chat-composer-dictate"),
                                            slotWidth = 48.dp,
                                        ) {
                                            Icon(painterResource(R.drawable.ic_chat_microphone), contentDescription = null,
                                                modifier = Modifier.size(24.dp), tint = Color.White)
                                        }
                                    }
                                }
                            }
                        } else Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
    }
    if (state.current.mode == Mode.Image && toolsPanel == ToolsPanel.Open) {
        ImageComposerTools(
            draft = draft,
            modelLabel = modelLabel.text,
            onPickReference = {
                toolsPanel = ToolsPanel.Closed
                pick()
            },
            onConfigure = {
                toolsPanel = ToolsPanel.Closed
                collapse()
                onConfigure()
            },
            onDismiss = { toolsPanel = ToolsPanel.Closed },
        )
    }
}

// During a configuration return, attachments and multi-line drafts reveal with the existing
// toolbar gate instead of adding their entire measured height on the first unfolded frame.
private fun Modifier.revealHeight(fraction: Float, folded: Dp) = clipToBounds().layout { child, constraints ->
    val measured = child.measure(constraints)
    val start = minOf(folded.roundToPx(), measured.height)
    val height = constraints.constrainHeight(start + ((measured.height - start) * fraction).roundToInt())
    layout(measured.width, height) { measured.place(0, 0) }
}

// Keep current text bounds relative to the trigger, including model changes while the picker is open.
private class ModelLabelMeasurement {
    var trigger: LayoutCoordinates? = null
    var label: LayoutCoordinates? = null

    fun record(picker: ChatModelPickerState) {
        val button = trigger?.takeIf { it.isAttached } ?: return
        val text = label?.takeIf { it.isAttached } ?: return
        val visible = button.localBoundingBoxOf(text).roundToIntRect()
            .intersect(IntRect(0, 0, button.size.width, button.size.height))
        if (visible.width > 0 && visible.height > 0) picker.modelLabelInTrigger = visible
    }
}

@Composable
private fun ComposerIconButton(label: String, onClick: () -> Unit, enabled: Boolean = true,
                               modifier: Modifier = Modifier, slotWidth: Dp = ToolbarActionSlot, glyph: @Composable () -> Unit) {
    Box(modifier.width(slotWidth).height(48.dp).clip(CircleShape)
        .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
        .semantics { contentDescription = label }, contentAlignment = Alignment.Center) { glyph() }
}

private fun DrawScope.narrowGlyphX(x: Float) = center.x + (x - center.x) * 0.90f

@Composable
private fun ToolGlyph(trackColor: Color, thumbColor: Color, modifier: Modifier) {
    Canvas(modifier.size(ComposerGlyphSize)) {
        val trackWidth = ComposerGlyphFineStroke.toPx()
        val thumbWidth = ComposerGlyphStroke.toPx()
        val margin = narrowGlyphX(trackWidth / 2)
        fun slider(row: Float, thumb: Float) {
            val y = row.dp.toPx()
            val x = narrowGlyphX(thumb.dp.toPx())
            drawLine(trackColor, Offset(margin, y), Offset(size.width - margin, y), trackWidth, StrokeCap.Round)
            drawLine(thumbColor, Offset(x, y - 1.8.dp.toPx()), Offset(x, y + 1.8.dp.toPx()), thumbWidth, StrokeCap.Round)
        }
        slider(3.5f, 11.5f)
        slider(9f, 5.5f)
        slider(14.5f, 10.5f)
    }
}

@Composable
private fun TerminalGlyph(promptColor: Color, underlineColor: Color, modifier: Modifier) {
    Canvas(modifier.size(ComposerGlyphSize)) {
        fun point(x: Float, y: Float) = Offset(narrowGlyphX(x.dp.toPx()), y.dp.toPx())
        val stroke = ComposerGlyphStroke.toPx()
        drawLine(promptColor, point(1f, 4f), point(6f, 9f), stroke, StrokeCap.Round)
        drawLine(promptColor, point(6f, 9f), point(1f, 14f), stroke, StrokeCap.Round)
        drawLine(underlineColor, point(9f, 14f), point(17f, 14f), ComposerGlyphFineStroke.toPx(), StrokeCap.Round)
    }
}

private val Attachment.hasImagePreview: Boolean
    get() = imageKey != null || (uri != null && mimeType?.startsWith("image/") == true)

@Composable
private fun DraftAttachment(attachment: Attachment, maxWidth: Dp, maxHeight: Dp, onRemove: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    if (attachment.hasImagePreview) {
        val key = attachment.imageKey
        val preview = if (key != null) {
            rememberGeneratedPreview(key, attachment.width ?: 1024, attachment.height ?: 1024, maxEdge = 480)
        } else {
            rememberAttachmentPreview(requireNotNull(attachment.uri))
        }
        // Imported files often have no stored dimensions. The decoded bitmap includes EXIF rotation.
        val bitmap = (preview as? PicturePreview.Ready)?.bitmap
        val w = bitmap?.width ?: attachment.width ?: 1
        val h = bitmap?.height ?: attachment.height ?: 1
        val ratio = if (w > 0 && h > 0) w.toFloat() / h else 1f
        // Give the remove action room even on very thin panoramas or portraits.
        val width = minOf(maxWidth, maxOf(32.dp, maxHeight * ratio))
        val height = minOf(maxHeight, maxOf(32.dp, maxWidth / ratio))
        Box(Modifier.size(width, height)
            .testTag("draftImage:${attachment.id}").clip(RoundedCornerShape(12.dp))) {
            BitmapPreview(preview, if (key != null) "参考图缩略图" else "图片附件", Modifier.matchParentSize(), fit = true)
            IconButton(onClick = onRemove, modifier = Modifier.align(Alignment.TopEnd).size(32.dp)) {
                Box(Modifier.size(22.dp).capsuleEdge().background(Color.Black.copy(alpha = .68f), CircleShape),
                    contentAlignment = Alignment.Center) {
                    Icon(Icons.Outlined.Close, "移除 ${attachment.name}", Modifier.size(15.dp),
                        tint = Color.White)
                }
            }
        }
        return
    }
    Row(
        Modifier.capsuleEdge().background(colors.surfaceContainerHighest, CircleShape).clip(CircleShape).padding(start = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(Icons.Outlined.AttachFile, null, Modifier.size(15.dp))
        Text(
            attachment.name,
            modifier = Modifier.widthIn(max = 140.dp),
            style = MaterialTheme.typography.labelMedium,
            overflow = TextOverflow.Ellipsis,
            maxLines = 1,
        )
        IconButton(onClick = onRemove, modifier = Modifier.size(36.dp)) {
            Icon(Icons.Outlined.Close, "移除 ${attachment.name}", Modifier.size(15.dp))
        }
    }
}
