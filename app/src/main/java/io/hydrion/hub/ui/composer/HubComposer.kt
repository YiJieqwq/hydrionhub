package io.hydrion.hub.ui.composer

import androidx.activity.compose.BackHandler
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import io.hydrion.hub.model.Attachment
import io.hydrion.hub.model.HubState
import io.hydrion.hub.model.HubViewModel
import io.hydrion.hub.model.Mode
import io.hydrion.hub.ui.messages.AttachmentPicture
import io.hydrion.hub.ui.messages.MockPicture
import io.hydrion.hub.ui.theme.HubStyle
import io.hydrion.hub.ui.theme.capsuleEdge

private const val ComposerTransitionMillis = 33
private val ComposerSecondary = Color(0xFFAFAFAF)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HubComposer(
    vm: HubViewModel,
    state: HubState,
    modelLabel: String,
    modelAvailable: Boolean,
    fast: Boolean,
    onConfigure: () -> Unit,
    onSend: () -> Unit,
    modifier: Modifier = Modifier,
    toolsAvailable: Boolean = true,
) {
    val colors = MaterialTheme.colorScheme
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val density = LocalDensity.current
    val windowFocused = LocalWindowInfo.current.isWindowFocused
    val imeVisible = WindowInsets.isImeVisible
    val nav = WindowInsets.navigationBars.getBottom(density)
    val ime = (WindowInsets.ime.getBottom(density) - nav).coerceAtLeast(0)
    val imeStart = (WindowInsets.imeAnimationSource.getBottom(density) - nav).coerceAtLeast(0)
    val imeEnd = (WindowInsets.imeAnimationTarget.getBottom(density) - nav).coerceAtLeast(0)
    var focused by remember { mutableStateOf(false) }
    var sawKeyboard by remember { mutableStateOf(false) }
    var sawHideAnimation by remember { mutableStateOf(false) }
    var previousImeFrame by remember { mutableStateOf<ComposerImeFrame?>(null) }
    val imeFrame = nextComposerImeFrame(previousImeFrame, ime, imeStart, imeEnd)
    var showTools by remember(state.current.id) { mutableStateOf(false) }
    var importError by remember(state.current.id) { mutableStateOf<String?>(null) }
    val pick = rememberAttachmentPicker(vm) { importError = it }

    fun collapse() {
        focusManager.clearFocus()
        keyboard?.hide()
    }
    val hiding = imeStart > 0 && imeEnd == 0
    val hiddenAfterEditing = sawKeyboard && !imeVisible && ime == 0 && imeEnd == 0
    SideEffect {
        previousImeFrame = imeFrame
        if (focused && (imeVisible || ime > 0)) sawKeyboard = true
        if (hiding && ime > 0) sawHideAnimation = true
    }
    // Some keyboards consume Back themselves. A hardware keyboard has no IME
    // height, so clear focus only after a software keyboard actually appeared.
    LaunchedEffect(hiddenAfterEditing, showTools) {
        if (hiddenAfterEditing && !showTools && focused) focusManager.clearFocus()
    }
    BackHandler(enabled = focused && !showTools) { collapse() }
    val editGate by animateFloatAsState(
        if (focused && windowFocused && !hiddenAfterEditing) 1f else 0f,
        tween(ComposerTransitionMillis, easing = LinearEasing), label = "composerFocus"
    )
    val imeFraction = imeFrame.fraction ?: if (ime == 0 && sawHideAnimation) 0f else 1f
    val expansion = minOf(editGate, imeFraction)

    val draft = state.draft
    val hasContent = draft.text.isNotEmpty() || draft.attachments.isNotEmpty()
    val rowGate by animateFloatAsState(if (hasContent) 1f else 0f,
        tween(ComposerTransitionMillis, easing = LinearEasing), label = "composerDraftRow")
    val toolbar = maxOf(expansion, rowGate)
    val canSend = modelAvailable && (draft.text.isNotBlank() || draft.attachments.isNotEmpty())
    val active = state.isGenerating
    val onAction: () -> Unit = {
        if (active) vm.stop() else if (canSend) {
            collapse()
            onSend()
        }
    }
    val sideInset = lerp(35.dp, 20.dp, toolbar) - 7.75.dp * expansion
    // The host has already consumed max(IME, navigation bars). On gesture navigation
    // the collapsed reference is measured from the physical edge, not the nav top.
    val compactGap = (35.dp - with(density) { nav.toDp() }).coerceAtLeast(8.dp)
    val bottomGap = lerp(compactGap, 6.dp, toolbar) + 6.25.dp * expansion
    val editorMinHeight = lerp(49.25.dp, 40.dp, toolbar) + 6.5.dp * expansion
    val controlRowHeight = 47.75.dp
    Column(modifier.fillMaxWidth().padding(start = sideInset, end = sideInset, bottom = bottomGap)) {
        importError?.let { error ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(error, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = colors.error)
                IconButton(onClick = { importError = null }) {
                    Icon(Icons.Outlined.Close, "关闭附件提示", Modifier.size(16.dp))
                }
            }
        }
        val shape = if (expansion == 0f) CircleShape else RoundedCornerShape(24.dp)
        Surface(
            modifier = Modifier.fillMaxWidth().testTag("composer-capsule").border(0.66.dp, HubStyle.ComposerBorder, shape),
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
                            value = draft.text,
                            onValueChange = vm::setDraft,
                            modifier = Modifier.fillMaxWidth().padding(
                                start = lerp(49.dp, 15.dp, toolbar), end = lerp(49.dp, 16.dp, toolbar)
                            ).onFocusChanged {
                                if (it.isFocused && !focused) {
                                    sawKeyboard = false
                                    sawHideAnimation = false
                                }
                                focused = it.isFocused
                            }.semantics { contentDescription = "消息输入框" },
                            textStyle = MaterialTheme.typography.bodyLarge.copy(color = colors.onSurface),
                            cursorBrush = SolidColor(colors.primary),
                            maxLines = if (hasContent || focused) 5 else 1,
                            decorationBox = { editor ->
                                Box(Modifier.fillMaxWidth().heightIn(min = editorMinHeight)
                                    .padding(vertical = 4.dp), contentAlignment = Alignment.CenterStart) {
                                    if (draft.text.isEmpty()) {
                                        Text(
                                            if (state.current.mode == Mode.Chat) "Chat with AI" else "描述想生成的图片",
                                            color = HubStyle.ComposerHint,
                                            style = MaterialTheme.typography.bodyLarge,
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
                        ComposerIconButton("添加附件", onClick = pick) { PlusGlyph() }
                        if (toolbar > 0f) {
                            val interactive = toolbar >= 0.6f
                            val reveal = Modifier.graphicsLayer {
                                alpha = toolbar
                                translationX = -8.dp.toPx() * (1f - toolbar)
                            }.then(if (interactive) Modifier else Modifier.clearAndSetSemantics { })
                            Row(reveal, verticalAlignment = Alignment.CenterVertically) {
                                ComposerIconButton("打开工具箱", onClick = {
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
                                Row(
                                    Modifier.clip(CircleShape).clickable(enabled = interactive, role = Role.Button) {
                                        collapse()
                                        onConfigure()
                                    }.heightIn(min = 44.dp).padding(horizontal = 4.dp),
                                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    if (fast) Icon(Icons.Outlined.Bolt, "快速", Modifier.size(15.dp), tint = colors.onSurface)
                                    Text(modelLabel, Modifier.weight(1f, fill = false),
                                        style = MaterialTheme.typography.labelLarge, color = colors.onSurface,
                                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Icon(Icons.Outlined.KeyboardArrowDown, "配置模型", Modifier.size(15.dp),
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
    Canvas(Modifier.size(17.dp)) {
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
    Canvas(Modifier.size(17.dp)) {
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
                MockPicture(key, attachment.width ?: 1024, attachment.height ?: 1024,
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
