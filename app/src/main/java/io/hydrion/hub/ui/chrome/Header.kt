package io.hydrion.hub.ui.chrome

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.hydrion.hub.R
import io.hydrion.hub.ui.theme.capsuleEdge
import io.hydrion.hub.model.Mode

internal object ChatHeaderLayout {
    val MaxWidth = 900.dp
    val SidePadding = 14.dp
    val TopPadding = 12.dp
    val ActionSize = 44.dp
}

private val DarkCapsuleFill = Color(0xFF303030)
private val DarkCapsuleBorder = Color(0xFF4F4F4F)
private const val BorderWidthPx = 2f

private val ColorScheme.darkHeader get() = surface.luminance() < onSurface.luminance()
private fun ColorScheme.capsuleFill(light: Color) = if (darkHeader) DarkCapsuleFill else light

@Composable
private fun headerInk(light: Color = LocalContentColor.current) =
    if (MaterialTheme.colorScheme.darkHeader) Color.White else light

private fun Modifier.headerEdge(colors: ColorScheme): Modifier {
    if (!colors.darkHeader) return capsuleEdge()
    return drawWithContent {
        drawContent()
        // Align both edges of the stroke to the pixel grid at every display density.
        val diameter = size.minDimension - BorderWidthPx
        if (diameter > 0f) drawRoundRect(
            color = DarkCapsuleBorder, topLeft = Offset(BorderWidthPx / 2, BorderWidthPx / 2),
            size = Size(size.width - BorderWidthPx, size.height - BorderWidthPx),
            cornerRadius = CornerRadius(diameter / 2f), style = Stroke(BorderWidthPx),
        )
    }
}

@Composable
internal fun Header(
    mode: Mode, locked: Boolean, title: String, pinned: Boolean, contextUsage: ContextUsage, environmentName: String? = null,
    onMode: (Mode) -> Unit, onDrawer: () -> Unit, onNew: () -> Unit,
    onRename: () -> Unit, onPin: () -> Unit, onFind: () -> Unit, onDelete: () -> Unit,
    contextWindow: ContextWindowState
) {
    BoxWithConstraints(
        Modifier.fillMaxWidth().testTag("floating-header")
            .padding(start = ChatHeaderLayout.SidePadding, top = ChatHeaderLayout.TopPadding,
                end = ChatHeaderLayout.SidePadding, bottom = 6.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        if (locked) {
            // Keep the same minimum text space before showing both actions.
            val compactActions = maxWidth < 262.dp + 125.dp * (LocalDensity.current.fontScale - 1f)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                DrawerButton(onDrawer)
                Spacer(Modifier.width(8.dp))
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                    ConversationChip(title, mode, environmentName, contextUsage, contextWindow, onRename)
                }
                Spacer(Modifier.width(6.dp))
                ConversationActions(pinned, compactActions, onNew, onPin, onFind, onDelete,
                    onRename.takeIf { mode == Mode.Chat }, beforeMenu = { contextWindow.open = false })
            }
        } else {
            DrawerButton(onDrawer)
            ModePicker(mode, onMode, Modifier.align(Alignment.Center))
        }
    }
}

@Composable
private fun DrawerButton(onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Surface(modifier = Modifier.testTag("header-drawer-capsule").headerEdge(colors), shape = CircleShape,
        color = colors.capsuleFill(colors.surfaceContainerHigh), contentColor = colors.onSurface) {
        IconButton(onClick = onClick, modifier = Modifier.size(ChatHeaderLayout.ActionSize)) {
            Icon(
                painterResource(R.drawable.ic_sidebar_menu),
                contentDescription = "打开侧栏", modifier = Modifier.size(24.dp), tint = headerInk()
            )
        }
    }
}

@Composable
private fun ModePicker(mode: Mode, onMode: (Mode) -> Unit, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier.headerEdge(colors).clip(CircleShape).background(colors.capsuleFill(colors.surfaceContainerHighest))
            .selectableGroup().padding(3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Mode.entries.forEach { option ->
            val selected = mode == option
            Box(
                Modifier.then(if (selected) Modifier.headerEdge(colors) else Modifier).clip(CircleShape)
                    .background(colors.capsuleFill(if (selected) colors.surface else colors.surfaceContainerHighest))
                    .selectable(selected = selected, role = Role.Tab, onClick = { onMode(option) })
                    .defaultMinSize(minWidth = 74.dp, minHeight = 36.dp).padding(horizontal = 10.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(option.name, maxLines = 1, style = MaterialTheme.typography.bodyMedium,
                    color = colors.onSurface, fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal)
            }
        }
    }
}

@Composable
private fun ConversationChip(
    title: String, mode: Mode, environmentName: String?, usage: ContextUsage, window: ContextWindowState, onRename: () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    val detailColor = Color(0xFFCDCDCD)
    val chat = mode == Mode.Chat
    val actionLabel = stringResource(if (!chat) R.string.header_rename_chat else if (window.open) R.string.context_close else R.string.context_open)
    val locked = stringResource(R.string.header_locked_mode)
    Column(
        Modifier.widthIn(max = 290.dp).testTag("conversation-chip")
            .then(if (chat) Modifier.onGloballyPositioned { window.anchor = it.boundsInRoot() } else Modifier)
            .headerEdge(colors).clip(CircleShape)
            .background(colors.capsuleFill(colors.surfaceContainerHigh))
            .clickable(role = Role.Button, onClickLabel = actionLabel) { if (chat) window.open = !window.open else onRename() }
            .semantics { stateDescription = locked }
            .heightIn(min = 44.dp).padding(start = 20.dp, top = 4.dp, end = 16.dp, bottom = 4.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 13.sp,
            lineHeight = 16.sp, fontWeight = FontWeight.SemiBold, color = headerInk(colors.onSurface))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            if (mode == Mode.Chat) {
                Icon(painterResource(R.drawable.ic_header_environment), null, Modifier.size(14.dp), tint = detailColor)
                Text(environmentName ?: stringResource(R.string.header_not_bound), Modifier.weight(1f, fill = false),
                    maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 12.sp, lineHeight = 16.sp,
                    color = if (environmentName == null) colors.error else detailColor)
            } else {
                Icon(Icons.Outlined.Lock, "已锁定", Modifier.size(11.dp), tint = detailColor)
                Text(mode.name, fontSize = 12.sp, lineHeight = 16.sp, maxLines = 1, color = detailColor)
            }
            val ringColor = if (usage.high) Color(0xFFEF4444) else Color(0xFF3B82F6)
            val reading = stringResource(R.string.context_used_of, usage.tokens?.toString() ?: stringResource(R.string.context_unmeasured),
                usage.window?.toString() ?: stringResource(R.string.context_unknown_limit))
            val ring = Modifier.size(11.dp).testTag("context-usage-ring").semantics { stateDescription = reading }
            if (usage.share(usage.tokens) == null) Spacer(ring.border(1.6.dp, detailColor, CircleShape))
            else CircularProgressIndicator(
                progress = { usage.fraction }, modifier = ring,
                strokeWidth = 1.6.dp,
                color = ringColor, trackColor = Color.White, strokeCap = StrokeCap.Butt,
                gapSize = 0.dp
            )
            usage.label?.let { Text(it, fontSize = 12.sp, lineHeight = 16.sp, maxLines = 1, color = detailColor) }
        }
    }
}

@Composable
private fun ConversationActions(
    pinned: Boolean, compact: Boolean, onNew: () -> Unit, onPin: () -> Unit, onFind: () -> Unit, onDelete: () -> Unit,
    onRename: (() -> Unit)?, beforeMenu: () -> Unit
) {
    var more by remember { mutableStateOf(false) }
    val colors = MaterialTheme.colorScheme
    Surface(modifier = Modifier.testTag("header-actions-capsule").headerEdge(colors), shape = CircleShape,
        color = colors.capsuleFill(colors.surfaceContainerHigh), contentColor = colors.onSurface) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (!compact) IconButton(onClick = onNew, modifier = Modifier.size(ChatHeaderLayout.ActionSize)) {
                Icon(painterResource(R.drawable.ic_compose), "新建会话", Modifier.size(22.dp), tint = headerInk())
            }
            Box {
                val moreWidth = if (compact) 40.dp else ChatHeaderLayout.ActionSize
                IconButton(onClick = { beforeMenu(); more = true }, modifier = Modifier.size(moreWidth, ChatHeaderLayout.ActionSize)) {
                    Icon(Icons.Outlined.MoreVert, "更多会话操作", Modifier.size(23.dp), tint = headerInk())
                }
                DropdownMenu(expanded = more, onDismissRequest = { more = false }) {
                    if (compact) DropdownMenuItem(text = { Text("新建会话") },
                        onClick = { more = false; onNew() })
                    if (onRename != null) DropdownMenuItem(text = { Text(stringResource(R.string.header_rename_chat)) },
                        onClick = { more = false; onRename() })
                    DropdownMenuItem(text = { Text(if (pinned) "取消置顶" else "置顶") },
                        onClick = { more = false; onPin() })
                    DropdownMenuItem(text = { Text("查找聊天内容") },
                        onClick = { more = false; onFind() })
                    DropdownMenuItem(text = { Text("删除会话") },
                        onClick = { more = false; onDelete() })
                }
            }
        }
    }
}
