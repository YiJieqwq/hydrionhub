package io.hydrion.hub.ui.composer

import android.content.res.Configuration
import android.provider.Settings
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
import androidx.compose.runtime.mutableFloatStateOf
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
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.rememberTextMeasurer
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
import io.hydrion.hub.model.LocalAssistant
import io.hydrion.hub.model.Mode
import io.hydrion.hub.ui.pickerCapsuleBounds
import io.hydrion.hub.ui.messages.BitmapPreview
import io.hydrion.hub.ui.messages.PicturePreview
import io.hydrion.hub.ui.messages.rememberAttachmentPreview
import io.hydrion.hub.ui.messages.rememberGeneratedPreview
import io.hydrion.hub.ui.theme.HubShapes
import io.hydrion.hub.ui.theme.HubStyle
import io.hydrion.hub.ui.theme.capsuleEdge
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlin.math.roundToInt

private data class PanelEditorReturn(val value: TextFieldValue, val imeHeight: Int, val modelPanel: Boolean)

private enum class ToolsPanel { Closed, WaitingForIme, Open }

internal val HubState.composerAssistantName: String
    get() = assistants.find { it.id == current.assistantId }?.name?.takeIf { it.isNotBlank() } ?: LocalAssistant.Default.name

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
    chatTools: ToolsPageState? = null,
    modelClock: PanelTravelClock? = null,
    panelContent: (@Composable () -> Unit)? = null,
) {
    val colors = MaterialTheme.colorScheme
    val darkComposer = colors.onSurface.luminance() > colors.surfaceContainerHigh.luminance()
    val glyphAccent = if (darkComposer) Color.White else colors.onSurface
    val glyphLine = if (darkComposer) Color.White else colors.onSurfaceVariant
    val chat = state.current.mode == Mode.Chat
    val assistantName = remember(state.current.assistantId, state.assistants) { state.composerAssistantName }
    val chatDraftHint = stringResource(R.string.chat_message_hint, assistantName)
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
    val browsing = chatPicker?.modelListNavigation == true
    val morphingEditor = panelContent != null
    val panelRequested = browsing || tools?.targetOpen == true
    val modelPanel = morphingEditor && tools?.active != true
    val panelClock = if (modelPanel) modelClock else tools?.clock
    val pageProgress = if (morphingEditor) panelClock?.progress ?: 0f else 0f
    val imeReturn = remember(state.current.id, state.current.mode, morphingEditor) { ComposerImeReturn() }
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
    var raisedDraft by remember(state.current.id, state.current.mode) { mutableStateOf<PanelEditorReturn?>(null) }
    val returningToEditor = !panelRequested && raisedDraft != null && (panelClock?.nearEditor != false || !morphingEditor)
    var requestedDraftFocus by remember(state.current.id, state.current.mode) { mutableStateOf(false) }
    val writableDraft = !morphingEditor || returningToEditor
    if (!morphingEditor && raisedDraft == null) {
        // Cache the actual invoking editor, before a panel takes focus or asks the IME to hide.
        val insetPx = (current.getBottom(density) - nav).coerceAtLeast(0)
        SideEffect {
            val inset = { (current.getBottom(density) - nav).coerceAtLeast(0) }
            tools?.clock?.prepareEditor(focused && insetPx > 0, inset)
            modelClock?.prepareEditor(focused && insetPx > 0, inset)
        }
    }
    LaunchedEffect(panelRequested) {
        if (panelRequested) {
            if (panelClock != null && panelClock.entryImeHeight > 0 && raisedDraft == null) {
                raisedDraft = PanelEditorReturn(editorValue.copy(composition = null), panelClock.entryImeHeight, modelPanel)
            }
            requestedDraftFocus = false
            collapse()
        } else if (morphingEditor) {
            // End a possible search input session before returning to the original editor.
            collapse()
        }
    }
    LaunchedEffect(returningToEditor, morphingEditor, allowImeReturn) {
        val origin = raisedDraft ?: return@LaunchedEffect
        val saved = origin.value
        if (returningToEditor && !requestedDraftFocus && allowImeReturn && windowFocused && saved.text == editorValue.text) {
            fieldValue = editorValue.copy(selection = saved.selection, composition = null)
            requestedDraftFocus = true
            editorFocus.requestFocus()
            keyboard?.show()
        }
        if (!morphingEditor) {
            if (requestedDraftFocus && allowImeReturn) withTimeoutOrNull(1_000) {
                snapshotFlow { current.getBottom(density) - nav }.first { it >= origin.imeHeight * .97f }
            }
            raisedDraft = null
        }
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
        if (morphingEditor && fieldValue.composition != null) fieldValue = fieldValue.copy(composition = null)
        if (focused && hasImeHeight) sawKeyboard = true
        if (hiding && hasImeHeight) sawHideAnimation = true
    }
    val editing = focused && !toolsRequested && !hiddenAfterEditing && (windowFocused || (sawKeyboard && imeActive))
    // A delayed click or text callback must still belong to this draft-editing session.
    val draftSession = remember(imeReturn) { mutableStateOf(true) }
    DisposableEffect(draftSession) { onDispose { draftSession.value = false } }
    fun acceptsDraftInput(): Boolean {
        val conversation = vm.state.value.current
        val draftAccepting = !morphingEditor && tools?.active != true || returningToEditor && requestedDraftFocus
        return draftSession.value && draftAccepting && conversation.id == state.current.id &&
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
            enabled = chat && allowImeReturn && !morphingEditor && !toolsRequested,
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
        enabled = allowImeReturn && !toolsRequested && !hardwareKeyboardOnly && !morphingEditor,
        editorFocused = focused, windowFocused = windowFocused && !dictation.prompting,
        imeShown = imeVisible || hasImeHeight, dismissing = hiding, opening = opening,
        clearAfterDismissal = hiddenAfterEditing && !toolsRequested && !morphingEditor,
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
    BackHandler(enabled = dictating || toolsPanel == ToolsPanel.WaitingForIme || (focused && !toolsRequested && !pickerOpen && !morphingEditor)) {
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
    val expansion = if ((hasImeHeight || hasImeTarget || hardwareOnly)) shapeProgress.value else 0f
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
    val rowGate = rememberDraftRowMotion(hasContent).fraction
    val chatToolbar = maxOf(expansion, rowGate)
    var lastToolbar by remember { mutableFloatStateOf(chatToolbar) }
    val restoringDock = !morphingEditor && raisedDraft != null
    if (!morphingEditor && !restoringDock) SideEffect { lastToolbar = chatToolbar }
    val actionRow = if (morphingEditor || restoringDock) lastToolbar else chatToolbar
    val contentProgress = if (morphingEditor) panelClock?.contentProgress ?: 0f else 0f
    val toolbar = actionRow * (1f - contentProgress)
    val draftFade = if (morphingEditor) Modifier.graphicsLayer { alpha = (1f - contentProgress / .45f).coerceIn(0f, 1f) } else Modifier
    val overlay: Any? = if (chatPicker == null || !allowImeReturn) null
        else chatPicker.page ?: tools?.takeIf { it.targetOpen }
    val effortAtEditor = overlay == ChatModelPickerState.Page.Controls && !morphingEditor
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
    // An empty folded chat uses the same physical bottom and side margin. Keep the parked draft's dock.
    val restingInset = if (chat) lerp(34.dp, 35.dp, rowGate) else 35.dp
    val expandedSide = if (chat) 12.dp else 12.25.dp
    val normalSide = lerp(restingInset, expandedSide, expansion)
    // Bottom insets have already been consumed by the host. A parked draft keeps its dock.
    val compactGap = (restingInset - with(density) { nav.toDp() }).coerceAtLeast(8.dp)
    val normalGap = lerp(compactGap, 12.25.dp, expansion)
    var lastEditorSide by remember { mutableStateOf(normalSide) }
    var lastEditorGap by remember { mutableStateOf(normalGap) }
    var parkedDraft by remember { mutableStateOf(false) }
    var entrySide by remember { mutableStateOf(normalSide) }
    val widthReturn = remember { Animatable(1f) }
    LaunchedEffect(morphingEditor) {
        if (morphingEditor) widthReturn.snapTo(0f)
        else if (widthReturn.value < 1f) widthReturn.animateTo(1f, tween(150))
    }
    val sideInset = when {
        morphingEditor -> lerp(lastEditorSide, expandedSide, pageProgress)
        restoringDock -> lastEditorSide
        else -> lerp(entrySide, normalSide, widthReturn.value)
    }
    val panelGap = if (parkedDraft) lastEditorGap else if (modelPanel || raisedDraft?.modelPanel == true) 8.dp else 0.dp
    val entryImeHeight = raisedDraft?.imeHeight ?: if (morphingEditor) panelClock?.entryImeHeight ?: 0 else 0
    val bottomGap = when {
        !morphingEditor && !restoringDock -> normalGap
        entryImeHeight > 0 -> {
            val followingIme = panelRequested && panelClock?.passedIme == false || !panelRequested && requestedDraftFocus
            val fraction = if (followingIme) ((current.getBottom(density) - nav).toFloat() / entryImeHeight).coerceIn(0f, 1f) else 0f
            lerp(panelGap, lastEditorGap, fraction)
        }
        else -> lerp(lastEditorGap, panelGap, pageProgress)
    }
    SideEffect {
        if (morphingEditor) entrySide = lastEditorSide
        else if (!restoringDock) {
            lastEditorSide = sideInset
            lastEditorGap = normalGap
            parkedDraft = hasContent && !imeActive && !hardwareOnly
        }
    }
    val foldedHeight = if (chat) 48.dp else FoldedEditorMinHeight
    val editorMinHeight = lerp(foldedHeight, 46.5.dp, toolbar)
    val controlRowHeight = 47.75.dp
    val editorTextStyle = MaterialTheme.typography.bodyLarge.copy(fontSize = 16.sp)
    val textTop = lerp(FoldedTextTop, OpenTextTop, toolbar)
    val safeArea = WindowInsets.safeDrawing
    // Keep the same measured node between the capsule and its parent when the picker opens.
    val pickerRoom = Modifier.layout { measurable, limits ->
        val constraints = if (pickerOpen && !morphingEditor) {
            val keep = (editorMinHeight + controlRowHeight + bottomGap).roundToPx()
            val height = pickerComposerMaxHeight(limits.maxHeight, safeArea.getTop(this), keep)
            limits.copy(maxHeight = height.coerceAtLeast(limits.minHeight))
        } else limits
        val composer = measurable.measure(constraints)
        layout(composer.width, composer.height) { composer.place(0, 0) }
    }
    Column(modifier.then(pickerRoom).fillMaxWidth().padding(start = sideInset, end = sideInset, bottom = bottomGap)) {
        importError?.takeIf { !morphingEditor }?.let { error ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(error, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = colors.error)
                IconButton(onClick = { importError = null }) {
                    Icon(Icons.Outlined.Close, "关闭附件提示", Modifier.size(16.dp))
                }
            }
        }
        val toolsPull = if (morphingEditor && !modelPanel) pageProgress else 0f
        val shape = when {
            !morphingEditor && (dictating || toolbar == 0f) -> CircleShape
            toolsPull > 0f -> {
                val top = lerp(HubShapes.LargeRadius, 20.dp, toolsPull)
                val bottom = lerp(HubShapes.LargeRadius, 0.dp, toolsPull)
                RoundedCornerShape(top, top, bottom, bottom)
            }
            else -> HubShapes.Large
        }
        val fill = if (toolsPull > 0f) androidx.compose.ui.graphics.lerp(colors.surfaceContainerHigh,
            toolPanelScheme().surface, toolsPull) else colors.surfaceContainerHigh
        Surface(
            modifier = Modifier.fillMaxWidth().testTag("composer-capsule")
                .then(if (chatPicker != null) Modifier.pickerCapsuleBounds() else Modifier)
                .then(if (chatPicker == null) Modifier else Modifier.onGloballyPositioned {
                    chatPicker.composerBounds = IntRect(it.positionInWindow().round(), it.size)
                })
                .then(if (toolsPull >= 1f) Modifier else Modifier.border(0.66.dp,
                    HubStyle.ComposerBorder.copy(alpha = HubStyle.ComposerBorder.alpha * (1f - toolsPull)), shape)),
            shape = shape,
            color = fill,
        ) {
            ComposerPanelBody(panelClock, panelRequested, returningToEditor, sideInset, expandedSide, bottomGap,
                if (entryImeHeight > 0) 0.dp else lastEditorGap - panelGap, 34.dp,
                handleId = if (modelPanel) "model" else "tools",
                dismiss = { if (modelPanel) chatPicker?.systemBack() else tools?.close() }, panel = panelContent) {
                Column {
                    if (!dictating && draft.attachments.isNotEmpty()) {
                        val imageAndText = draft.text.isNotEmpty() && draft.attachments.any { it.hasImagePreview }
                        // Let the editor and toolbar measure first in short windows above the IME.
                        BoxWithConstraints(Modifier.weight(1f, fill = false).fillMaxWidth()
                            .then(when {
                                morphingEditor -> draftFade.clearAndSetSemantics { }.revealHeight(1f - contentProgress, 0.dp)
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
                                        if (!morphingEditor && tools?.active != true && chatPicker?.modelListNavigation != true) vm.removeAttachment(attachment.id)
                                    }
                                }
                            }
                        }
                    }
                    Box(Modifier.fillMaxWidth()) {
                        // Keep the native input connection alive beneath the one-line voice controls.
                        val editorLayer = if (dictating) Modifier.height(48.dp)
                            .clearAndSetSemantics { }.drawWithContent { } else Modifier
                        Column(editorLayer.then(draftFade)) {
                            ComposerEditorSlot(
                                editorFocus = editorFocus, modelEditorBridge = modelEditorBridge,
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
                                modifier = (if (pickerOpen && !morphingEditor) Modifier.weight(1f, fill = false) else Modifier),
                            ) { draftFocusModifier ->
                                val inputHint = if (chat) chatDraftHint else "描述想生成的图片"
                                BasicTextField(
                                    value = editorValue,
                                    readOnly = !writableDraft,
                                    onValueChange = { if (!dictation.isActive && acceptsDraftInput()) updateDraft(it) },
                                    modifier = Modifier.fillMaxWidth().padding(
                                        start = lerp(FoldedTextStart, OpenTextStart, toolbar), end = lerp(FoldedTextStart, OpenTextEnd, toolbar)
                                    ).chatModelPickerPreImeBack(pickerOpen && !browsing && !morphingEditor) { chatPicker?.systemBack() }
                                        .then(draftFocusModifier)
                                        .semantics {
                                            contentDescription = "消息输入框"
                                            if (effortAtEditor) onClick {
                                                if (!acceptsDraftInput()) return@onClick false
                                                chatPicker?.dismissEffort()
                                                if (!focused) editorFocus.requestFocus()
                                                if (!imeActive && !hardwareKeyboardOnly) keyboard?.show()
                                                true
                                            }
                                        },
                                    textStyle = editorTextStyle.copy(
                                        color = colors.onSurface,
                                        letterSpacing = if (chat) 0.1.sp else editorTextStyle.letterSpacing,
                                    ),
                                    cursorBrush = SolidColor(colors.primary),
                                    maxLines = if (hasContent || focused) 5 else 1,
                                    decorationBox = { editor ->
                                        Box(Modifier.fillMaxWidth()
                                            .trackPickerEditor(chatPicker)
                                            .then(if (effortAtEditor) editorTap else Modifier)
                                            .heightIn(min = editorMinHeight)
                                            .padding(top = textTop, bottom = TextBottom),
                                            contentAlignment = Alignment.CenterStart) {
                                            val visibleTextLine = if (chatPicker != null)
                                                Modifier.testTag("composer-editable-text").then(if (effortAtEditor) Modifier else editorTap)
                                            else Modifier
                                            Box(Modifier.fillMaxWidth().trackPickerEditor(chatPicker, textOnly = true)
                                                .then(visibleTextLine).panelPromptOrigin(), contentAlignment = Alignment.CenterStart) {
                                                if (editorValue.text.isEmpty()) {
                                                    val hintStyle = if (chat) editorTextStyle else editorTextStyle.copy(fontSize = 17.sp)
                                                    PanelPromptText(inputHint, color = HubStyle.ComposerHint, style = hintStyle, fromEditor = true)
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
                            progress = 0f, toolsOpen = false, trackModel = chatPicker != null,
                            hasClearableTools = false, generating = active, canSend = canSend, chat = chat,
                            onAttach = { if (acceptsDraftInput()) pick() }, onSend = onAction, onClear = {}, onBack = {},
                            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                                .height(lerp(editorMinHeight, controlRowHeight, actionRow))
                                .graphicsLayer {
                                    translationY = size.height * contentProgress
                                    scaleX = 1f - .36f * contentProgress
                                    scaleY = scaleX
                                    alpha = 1f - contentProgress
                                }.testTag("composer-action-row")
                                .padding(start = ToolbarTouchOutset, end = ToolbarTouchOutset + if (chat) 2.dp else 0.dp),
                        ) { leading, trailing ->
                            if (actionRow > 0f) {
                                val interactive = chatToolbar >= 0.6f && !morphingEditor
                                val reveal = Modifier.graphicsLayer {
                                    alpha = actionRow
                                    translationX = -8.dp.toPx() * (1f - actionRow)
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
                                        DisposableEffect(chatPicker) { onDispose { chatPicker.triggerBounds = null } }
                                        Modifier.testTag("chat-model-trigger").onGloballyPositioned {
                                            modelText.trigger = it
                                            chatPicker.triggerBounds = IntRect(it.positionInWindow().round(), it.size)
                                            modelText.record(chatPicker)
                                        }
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
                                                val textBounds = if (chatPicker != null)
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
                                                enabled = interactive && allowImeReturn,
                                                modifier = Modifier.padding(end = ToolbarTouchOutset).testTag("chat-composer-dictate"),
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
            .testTag("draftImage:${attachment.id}").clip(HubShapes.Small)) {
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
        Modifier.capsuleEdge(HubShapes.Small).background(colors.surfaceContainerHighest, HubShapes.Small)
            .clip(HubShapes.Small).padding(start = 10.dp),
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
