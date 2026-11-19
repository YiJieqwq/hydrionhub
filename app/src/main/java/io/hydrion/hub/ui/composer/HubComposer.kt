package io.hydrion.hub.ui.composer

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.LinearEasing
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.hydrion.hub.model.Attachment
import io.hydrion.hub.model.HubState
import io.hydrion.hub.model.HubViewModel
import io.hydrion.hub.model.Mode
import io.hydrion.hub.ui.messages.AttachmentPicture
import io.hydrion.hub.ui.messages.MockPicture
import io.hydrion.hub.ui.theme.HubStyle
import io.hydrion.hub.ui.theme.capsuleEdge
import kotlinx.coroutines.delay

private const val ComposerMotionMillis = 33

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
    val imeVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    val inputFocus = remember { FocusRequester() }
    var focused by remember { mutableStateOf(false) }
    var preparingIme by remember(state.current.id) { mutableStateOf(false) }
    val expanded = focused || preparingIme
    var wasImeVisible by remember(state.current.id) { mutableStateOf(false) }
    var showTools by remember(state.current.id) { mutableStateOf(false) }
    var importError by remember(state.current.id) { mutableStateOf<String?>(null) }
    val pick = rememberAttachmentPicker(vm) { importError = it }

    fun collapse() {
        focusManager.clearFocus()
        keyboard?.hide()
        preparingIme = false
    }
    // Let the compact shell finish opening before requesting an input connection.
    // From there the parent follows Android IME insets with no additional translation.
    LaunchedEffect(preparingIme) {
        if (preparingIme) {
            withFrameNanos { }
            delay(ComposerMotionMillis.toLong())
            inputFocus.requestFocus()
            keyboard?.show()
            preparingIme = false
        }
    }
    // Some IMEs consume the first system-back event. Observe their dismissal as well.
    LaunchedEffect(imeVisible, showTools) {
        if (!imeVisible && wasImeVisible && focused && !showTools) focusManager.clearFocus()
        wasImeVisible = imeVisible
    }
    BackHandler(enabled = expanded && !showTools) { collapse() }

    val draft = state.draft
    val hasContent = draft.text.isNotEmpty() || draft.attachments.isNotEmpty()
    val toolbarVisible = expanded || hasContent
    val canSend = modelAvailable && (draft.text.isNotBlank() || draft.attachments.isNotEmpty())
    val active = state.isGenerating
    val onAction: () -> Unit = {
        if (active) vm.stop() else if (canSend) {
            collapse()
            onSend()
        }
    }
    val sideInset by animateDpAsState(
        targetValue = when {
            expanded -> 9.dp
            hasContent -> 20.dp
            else -> 30.dp
        },
        animationSpec = tween(ComposerMotionMillis, easing = LinearEasing),
        label = "composerSideInset",
    )
    Column(modifier.fillMaxWidth().padding(start = sideInset, end = sideInset, top = 4.dp, bottom = 6.dp)) {
        importError?.let { error ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(error, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = colors.error)
                IconButton(onClick = { importError = null }) {
                    Icon(Icons.Outlined.Close, "关闭附件提示", Modifier.size(16.dp))
                }
            }
        }
        val shape = RoundedCornerShape(27.dp)
        Surface(
            modifier = Modifier.fillMaxWidth().capsuleEdge(shape),
            shape = shape,
            color = colors.surfaceContainerHigh,
        ) {
            Column(
                Modifier.animateContentSize(tween(ComposerMotionMillis, easing = LinearEasing))
                    .padding(horizontal = if (expanded) 5.dp else 3.dp, vertical = if (expanded) 3.dp else 0.dp)
            ) {
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
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (!toolbarVisible) AddButton(pick)
                    Box(Modifier.weight(1f)) {
                        BasicTextField(
                            value = draft.text,
                            onValueChange = vm::setDraft,
                            modifier = Modifier.fillMaxWidth().focusRequester(inputFocus).onFocusChanged { focused = it.isFocused }
                                .semantics { contentDescription = "消息输入框" },
                            textStyle = MaterialTheme.typography.bodyLarge.copy(color = colors.onSurface),
                            cursorBrush = SolidColor(colors.primary),
                            maxLines = if (toolbarVisible) 5 else 1,
                            decorationBox = { editor ->
                                Box(
                                    Modifier.fillMaxWidth().heightIn(min = if (hasContent && !expanded) 40.dp else 46.dp)
                                        .padding(
                                            start = if (toolbarVisible) 14.dp else 2.dp, end = 8.dp,
                                            top = if (expanded) 8.dp else 4.dp, bottom = 4.dp,
                                        ),
                                    contentAlignment = Alignment.CenterStart,
                                ) {
                                    if (draft.text.isEmpty()) {
                                        Text(
                                            if (state.current.mode == Mode.Chat) "Chat with AI" else "描述想生成的图片",
                                            color = HubStyle.ComposerHint,
                                            style = MaterialTheme.typography.bodyLarge,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                    editor()
                                }
                            },
                        )
                        if (!toolbarVisible) {
                            Box(Modifier.matchParentSize().pointerInput(Unit) {
                                detectTapGestures { preparingIme = true }
                            })
                        }
                    }
                    if (!toolbarVisible) SendButton(active, canSend, onAction)
                }
                if (toolbarVisible) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        AddButton(pick)
                        IconButton(onClick = {
                            showTools = true
                            keyboard?.hide()
                        }, enabled = toolsAvailable) {
                            Icon(Icons.Outlined.Tune, "打开工具箱", Modifier.size(21.dp),
                                tint = colors.onSurface.copy(alpha = if (toolsAvailable) 1f else 0.38f))
                        }
                        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                            Row(
                                Modifier.clip(CircleShape).clickable(role = Role.Button) {
                                    collapse()
                                    onConfigure()
                                }.heightIn(min = 44.dp).padding(start = 6.dp, end = 4.dp),
                                horizontalArrangement = Arrangement.spacedBy(2.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                if (fast) Icon(Icons.Outlined.Bolt, "快速", Modifier.size(15.dp), tint = colors.onSurface)
                                Text(
                                    modelLabel,
                                    Modifier.weight(1f, fill = false),
                                    style = MaterialTheme.typography.labelLarge,
                                    color = colors.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Icon(Icons.Outlined.KeyboardArrowDown, "配置模型", Modifier.size(15.dp),
                                    tint = colors.onSurfaceVariant)
                            }
                        }
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
private fun AddButton(onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        Icon(Icons.Outlined.Add, "添加附件", Modifier.size(24.dp), tint = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun SendButton(generating: Boolean, canSend: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val enabled = generating || canSend
    IconButton(onClick = onClick, enabled = enabled) {
        val fill = if (enabled) colors.primary else colors.surfaceContainerHighest
        val ink = if (enabled) colors.onPrimary else colors.onSurfaceVariant
        Box(Modifier.size(36.dp).capsuleEdge().background(fill, CircleShape), contentAlignment = Alignment.Center) {
            if (generating) {
                Box(
                    Modifier.size(13.dp).background(ink, RoundedCornerShape(3.dp))
                        .semantics { contentDescription = "停止生成" },
                )
            } else {
                Icon(Icons.Outlined.ArrowUpward, "发送消息", Modifier.size(22.dp), tint = ink)
            }
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
