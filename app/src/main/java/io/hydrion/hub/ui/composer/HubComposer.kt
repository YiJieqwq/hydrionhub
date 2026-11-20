package io.hydrion.hub.ui.composer

import android.content.res.Configuration
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.round
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import androidx.compose.ui.unit.sp
import io.hydrion.hub.R
import io.hydrion.hub.provider.ChatModelPickerState
import io.hydrion.hub.provider.chatModelPickerPreImeBack
import io.hydrion.hub.model.Attachment
import io.hydrion.hub.model.HubState
import io.hydrion.hub.model.HubViewModel
import io.hydrion.hub.model.Mode
import io.hydrion.hub.ui.messages.AttachmentPicture
import io.hydrion.hub.ui.messages.GeneratedPicture
import io.hydrion.hub.ui.theme.HubStyle
import io.hydrion.hub.ui.theme.capsuleEdge
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

private const val ComposerTransitionMillis = 33
private val ComposerSecondary = Color(0xFFAFAFAF)
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
    modelLabel: String,
    modelAvailable: Boolean,
    fast: Boolean,
    onConfigure: () -> Unit,
    onSend: () -> Unit,
    modifier: Modifier = Modifier,
    toolsAvailable: Boolean = true,
    allowImeReturn: Boolean = true,
    chatPicker: ChatModelPickerState? = null,
) {
    val colors = MaterialTheme.colorScheme
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
    val imeReturn = remember(state.current.id, state.current.mode) { ComposerImeReturn() }
    val editorFocus = remember { FocusRequester() }
    val draft = state.draft
    var fieldValue by remember(state.current.id, state.current.mode) {
        mutableStateOf(TextFieldValue(draft.text))
    }
    val editorValue = if (fieldValue.text == draft.text) fieldValue else fieldValue.copy(text = draft.text, composition = null)
    var focused by remember { mutableStateOf(false) }
    var sawKeyboard by remember { mutableStateOf(false) }
    var sawHideAnimation by remember { mutableStateOf(false) }
    var showTools by remember(state.current.id) { mutableStateOf(false) }
    var importError by remember(state.current.id) { mutableStateOf<String?>(null) }
    val launchPicker = rememberAttachmentPicker(vm) { importError = it }
    fun pick() {
        imeReturn.cancel()
        launchPicker()
    }

    fun collapse() {
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
    val hardwareOnly = hardwareKeyboardOnly && !hiddenAfterEditing && !sawHideAnimation
    SideEffect {
        if (fieldValue.text != draft.text) fieldValue = editorValue
        if (focused && hasImeHeight) sawKeyboard = true
        if (hiding && hasImeHeight) sawHideAnimation = true
    }
    ComposerImeReturnEffect(
        controller = imeReturn,
        enabled = allowImeReturn && !showTools && !hardwareKeyboardOnly,
        editorFocused = focused, windowFocused = windowFocused,
        imeShown = imeVisible || hasImeHeight, dismissing = hiding, opening = opening,
        clearAfterDismissal = hiddenAfterEditing && !showTools,
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
    val pickerOpen = chatPicker?.page != null
    BackHandler(enabled = focused && !showTools && !pickerOpen) { collapse() }
    // A system overlay can take window focus while the editor still owns a visible IME.
    val editing = focused && !showTools && !hiddenAfterEditing && (windowFocused || (sawKeyboard && imeActive))
    val shapeProgress = rememberComposerShape {
        ComposerMotionInput(
            (current.getBottom(density) - nav).coerceAtLeast(0),
            (source.getBottom(density) - nav).coerceAtLeast(0),
            (target.getBottom(density) - nav).coerceAtLeast(0),
            editing, hardwareOnly, imeVisible,
        )
    }
    // Legacy keyboards may jump straight to zero before the flow collector sees the final inset.
    val expansion = if (hasImeHeight || hasImeTarget || hardwareOnly) shapeProgress.value else 0f

    val hasContent = draft.text.isNotEmpty() || draft.attachments.isNotEmpty()
    val rowGate by animateFloatAsState(if (hasContent) 1f else 0f,
        tween(ComposerTransitionMillis, easing = LinearEasing), label = "composerDraftRow")
    val toolbar = maxOf(expansion, rowGate)
    val canSend = (modelAvailable || state.current.mode == Mode.Image) &&
        (draft.text.isNotBlank() || draft.attachments.isNotEmpty())
    val active = state.isGenerating
    val onAction: () -> Unit = {
        if (active) vm.stop() else if (canSend) {
            collapse()
            onSend()
        }
    }
    val sideInset = lerp(35.dp, 12.25.dp, expansion)
    // The host has already consumed max(IME, navigation bars). On gesture navigation
    // the collapsed reference is measured from the physical edge, not the nav top.
    val compactGap = (35.dp - with(density) { nav.toDp() }).coerceAtLeast(8.dp)
    val bottomGap = lerp(compactGap, 12.25.dp, expansion)
    val editorMinHeight = lerp(49.25.dp, 46.5.dp, toolbar)
    val controlRowHeight = 47.75.dp
    val editorTextStyle = MaterialTheme.typography.bodyLarge.copy(fontSize = 17.sp)
    Column(modifier.fillMaxWidth().padding(start = sideInset, end = sideInset, bottom = bottomGap)) {
        importError?.let { error ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(error, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = colors.error)
                IconButton(onClick = { importError = null }) {
                    Icon(Icons.Outlined.Close, "关闭附件提示", Modifier.size(16.dp))
                }
            }
        }
        val shape = if (toolbar == 0f) CircleShape else RoundedCornerShape(24.dp)
        Surface(
            modifier = Modifier.fillMaxWidth().testTag("composer-capsule")
                .then(if (chatPicker == null) Modifier else Modifier.onGloballyPositioned {
                    chatPicker.composer = IntRect(it.positionInWindow().round(), it.size)
                }).border(0.66.dp, HubStyle.ComposerBorder, shape),
            shape = shape,
            color = colors.surfaceContainerHigh,
        ) {
            Column {
                if (draft.attachments.isNotEmpty()) {
                    LazyRow(
                        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 3.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        items(draft.attachments, key = { it.id }) { attachment ->
                            DraftAttachment(attachment) { vm.removeAttachment(attachment.id) }
                        }
                    }
                }
                Box(Modifier.fillMaxWidth()) {
                    Column {
                        BasicTextField(
                            value = editorValue,
                            onValueChange = { updated ->
                                fieldValue = updated
                                if (updated.text != vm.state.value.draft.text) vm.setDraft(updated.text)
                            },
                            modifier = Modifier.fillMaxWidth().padding(
                                start = lerp(48.dp, 15.dp, toolbar), end = lerp(48.dp, 16.dp, toolbar)
                            ).chatModelPickerPreImeBack(pickerOpen) { chatPicker?.back() }
                                .focusRequester(editorFocus).onFocusChanged {
                                    if (it.isFocused && !focused) {
                                        sawKeyboard = false
                                        sawHideAnimation = false
                                    }
                                    focused = it.isFocused
                                }.semantics { contentDescription = "消息输入框" },
                            textStyle = editorTextStyle.copy(color = colors.onSurface),
                            cursorBrush = SolidColor(colors.primary),
                            maxLines = if (hasContent || focused) 5 else 1,
                            decorationBox = { editor ->
                                Box(Modifier.fillMaxWidth().heightIn(min = editorMinHeight)
                                    .padding(top = lerp(5.dp, 16.dp, toolbar), bottom = 4.dp),
                                    contentAlignment = Alignment.CenterStart) {
                                    if (draft.text.isEmpty()) {
                                        val isChat = state.current.mode == Mode.Chat
                                        Text(
                                            if (isChat) "Chat with AI" else "描述想生成的图片",
                                            color = HubStyle.ComposerHint,
                                            style = if (isChat) editorTextStyle.copy(fontSize = 16.sp) else editorTextStyle,
                                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                    editor()
                                }
                            },
                        )
                        Spacer(Modifier.height(controlRowHeight * toolbar))
                    }
                    Row(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(lerp(editorMinHeight, controlRowHeight, toolbar))
                        .padding(horizontal = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                        ComposerIconButton("添加附件", onClick = ::pick) { PlusGlyph() }
                        if (toolbar > 0f) {
                            val interactive = toolbar >= 0.6f
                            val reveal = Modifier.graphicsLayer {
                                alpha = toolbar
                                translationX = -8.dp.toPx() * (1f - toolbar)
                            }.then(if (interactive) Modifier else Modifier.clearAndSetSemantics { })
                            Row(reveal, verticalAlignment = Alignment.CenterVertically) {
                                ComposerIconButton("打开工具箱", onClick = {
                                    imeReturn.cancel()
                                    showTools = true
                                    keyboard?.hide()
                                }, enabled = interactive && toolsAvailable) {
                                    ToolGlyph()
                                }
                                Box(Modifier.width(42.dp).height(48.dp).clearAndSetSemantics {
                                    contentDescription = "工作区终端"
                                    role = Role.Button
                                    disabled()
                                }, contentAlignment = Alignment.Center) { TerminalGlyph() }
                            }
                            Box(Modifier.weight(1f).then(reveal), contentAlignment = Alignment.CenterEnd) {
                                val anchor = if (chatPicker == null) Modifier else {
                                    DisposableEffect(chatPicker) { onDispose { chatPicker.dismiss() } }
                                    Modifier.testTag("chat-model-trigger").onGloballyPositioned {
                                        chatPicker.anchor = IntRect(it.positionInWindow().round(), it.size)
                                    }
                                }
                                Row(
                                    anchor.clip(CircleShape).clickable(enabled = interactive, role = Role.Button) {
                                        if (chatPicker != null) chatPicker.open() else {
                                            collapse()
                                            onConfigure()
                                        }
                                    }.heightIn(min = 44.dp).padding(horizontal = 4.dp),
                                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    if (fast) Icon(Icons.Outlined.Bolt, "快速", Modifier.size(15.dp), tint = colors.onSurface)
                                    Text(modelLabel, Modifier.weight(1f, fill = false),
                                        style = MaterialTheme.typography.labelLarge, color = colors.onSurface,
                                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Icon(Icons.Outlined.KeyboardArrowDown,
                                        if (chatPicker != null) stringResource(R.string.chat_controls_select_model)
                                        else "配置模型", Modifier.size(15.dp),
                                        tint = colors.onSurfaceVariant)
                                }
                            }
                        } else Spacer(Modifier.weight(1f))
                        SendButton(active, canSend, onAction)
                    }
                }
            }
        }
    }
    if (showTools) {
        ComposerTools(
            mode = state.current.mode,
            draft = draft,
            modelLabel = modelLabel,
            onSearch = vm::setSearch,
            onImageTool = vm::setImageTool,
            onPickReference = {
                showTools = false
                pick()
            },
            onConfigure = {
                showTools = false
                collapse()
                onConfigure()
            },
            onDismiss = { showTools = false },
        )
    }
}

@Composable
private fun ComposerIconButton(label: String, onClick: () -> Unit, enabled: Boolean = true,
                               glyph: @Composable () -> Unit) {
    Box(Modifier.width(42.dp).height(48.dp).clip(CircleShape)
        .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
        .semantics { contentDescription = label }, contentAlignment = Alignment.Center) { glyph() }
}

@Composable
private fun PlusGlyph() {
    Canvas(Modifier.size(17.dp)) {
        val stroke = 1.8.dp.toPx()
        val r = size.minDimension / 2 - stroke / 2
        drawLine(Color.White, Offset(center.x - r, center.y), Offset(center.x + r, center.y), stroke, StrokeCap.Round)
        drawLine(Color.White, Offset(center.x, center.y - r), Offset(center.x, center.y + r), stroke, StrokeCap.Round)
    }
}

@Composable
private fun ToolGlyph() {
    // Bring the glyphs closer to the attachment icon inside their existing hit areas.
    Canvas(Modifier.offset(x = -ToolbarGlyphGapReduction).size(17.dp)) {
        val stroke = 1.6.dp.toPx()
        val margin = stroke / 2
        fun slider(row: Float, thumb: Float) {
            val y = row.dp.toPx()
            val x = thumb.dp.toPx()
            drawLine(ComposerSecondary, Offset(margin, y), Offset(size.width - margin, y), stroke, StrokeCap.Round)
            drawLine(ComposerSecondary, Offset(x, y - 1.8.dp.toPx()), Offset(x, y + 1.8.dp.toPx()), stroke, StrokeCap.Round)
        }
        slider(3f, 11f)
        slider(8.5f, 5f)
        slider(14f, 10f)
    }
}

@Composable
private fun TerminalGlyph() {
    Canvas(Modifier.offset(x = -ToolbarGlyphGapReduction * 2).size(17.dp)) {
        fun point(x: Float, y: Float) = Offset(x.dp.toPx(), y.dp.toPx())
        val stroke = 1.8.dp.toPx()
        val ink = ComposerSecondary
        drawLine(ink, point(1f, 3.5f), point(6f, 8.5f), stroke, StrokeCap.Round)
        drawLine(ink, point(6f, 8.5f), point(1f, 13.5f), stroke, StrokeCap.Round)
        drawLine(ink, point(8f, 13.5f), point(16f, 13.5f), stroke, StrokeCap.Round)
    }
}

@Composable
private fun SendButton(generating: Boolean, canSend: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val enabled = generating || canSend
    ComposerIconButton(if (generating) "停止生成" else "发送消息", onClick, enabled) {
        val fill = when {
            generating -> Color.White
            canSend -> colors.primary
            else -> colors.surfaceContainerHighest
        }
        Box(Modifier.size(32.dp).then(if (generating) Modifier else Modifier.capsuleEdge())
            .background(fill, CircleShape), contentAlignment = Alignment.Center) {
            if (generating) Box(Modifier.size(11.dp).background(Color.Black, RoundedCornerShape(1.25.dp)))
            else Icon(Icons.Outlined.ArrowUpward, null, Modifier.size(20.dp),
                tint = if (canSend) colors.onPrimary else colors.onSurfaceVariant)
        }
    }
}

@Composable
private fun DraftAttachment(attachment: Attachment, onRemove: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val key = attachment.imageKey
    val uri = attachment.uri
    if (key != null || (uri != null && attachment.mimeType?.startsWith("image/") == true)) {
        Box(Modifier.size(72.dp).clip(RoundedCornerShape(12.dp))) {
            if (key != null) {
                GeneratedPicture(key, attachment.width ?: 1024, attachment.height ?: 1024,
                    "参考图缩略图", Modifier.matchParentSize())
            } else if (uri != null) AttachmentPicture(uri, Modifier.matchParentSize())
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
