package io.hydrion.hub.ui.chrome

import androidx.compose.foundation.background
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
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
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

@Composable
internal fun Header(
    mode: Mode, locked: Boolean, title: String, pinned: Boolean, contextUsage: ContextUsage,
    onMode: (Mode) -> Unit, onDrawer: () -> Unit, onNew: () -> Unit,
    onRename: () -> Unit, onPin: () -> Unit, onFind: () -> Unit, onDelete: () -> Unit
) {
    BoxWithConstraints(
        Modifier.fillMaxWidth().testTag("floating-header")
            .padding(start = 14.dp, top = 12.dp, end = 14.dp, bottom = 6.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        if (locked) {
            val compactActions = maxWidth < 252.dp + 125.dp * (LocalDensity.current.fontScale - 1f)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                DrawerButton(onDrawer)
                Spacer(Modifier.width(6.dp))
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                    ConversationChip(title, mode, contextUsage, onRename)
                }
                Spacer(Modifier.width(6.dp))
                ConversationActions(pinned, compactActions, onNew, onPin, onFind, onDelete)
            }
        } else {
            DrawerButton(onDrawer)
            ModePicker(mode, onMode, Modifier.align(Alignment.Center))
        }
    }
}

@Composable
private fun DrawerButton(onClick: () -> Unit) {
    Surface(modifier = Modifier.testTag("header-drawer-capsule").capsuleEdge(), shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurface) {
        IconButton(onClick = onClick, modifier = Modifier.size(44.dp)) {
            Icon(
                painterResource(R.drawable.ic_sidebar_menu),
                contentDescription = "打开侧栏", modifier = Modifier.size(24.dp)
            )
        }
    }
}

@Composable
private fun ModePicker(mode: Mode, onMode: (Mode) -> Unit, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier.capsuleEdge().clip(CircleShape).background(colors.surfaceContainerHighest)
            .selectableGroup().padding(3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Mode.entries.forEach { option ->
            val selected = mode == option
            Box(
                Modifier.then(if (selected) Modifier.capsuleEdge() else Modifier).clip(CircleShape)
                    .background(if (selected) colors.surface else colors.surfaceContainerHighest)
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
    title: String, mode: Mode, usage: ContextUsage, onRename: () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    val detailColor = Color(0xFFCDCDCD)
    Column(
        Modifier.widthIn(max = 290.dp).testTag("conversation-chip").capsuleEdge().clip(CircleShape).background(colors.surfaceContainerHigh)
            .clickable(role = Role.Button, onClickLabel = "重命名会话", onClick = onRename)
            .semantics { stateDescription = "模式已锁定" }
            .heightIn(min = 44.dp).padding(horizontal = 16.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 13.sp,
            lineHeight = 16.sp, fontWeight = FontWeight.SemiBold, color = colors.onSurface)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Icon(Icons.Outlined.Lock, "已锁定", Modifier.size(11.dp), tint = detailColor)
            Text(mode.name, fontSize = 12.sp, lineHeight = 16.sp, maxLines = 1, color = detailColor)
            val ringColor = if (usage.high) Color(0xFFEF4444) else Color(0xFF3B82F6)
            CircularProgressIndicator(
                progress = { usage.fraction }, modifier = Modifier.size(11.dp).testTag("context-usage-ring")
                    .semantics { stateDescription = "${usage.tokens ?: "—"} / ${usage.window} tokens" },
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
    pinned: Boolean, compact: Boolean, onNew: () -> Unit, onPin: () -> Unit, onFind: () -> Unit, onDelete: () -> Unit
) {
    var more by remember { mutableStateOf(false) }
    Surface(modifier = Modifier.testTag("header-actions-capsule").capsuleEdge(), shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurface) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (!compact) IconButton(onClick = onNew, modifier = Modifier.size(44.dp)) {
                Icon(painterResource(R.drawable.ic_compose), "新建会话", Modifier.size(22.dp))
            }
            Box {
                IconButton(onClick = { more = true }, modifier = Modifier.size(40.dp, 44.dp)) {
                    Icon(Icons.Outlined.MoreVert, "更多会话操作", Modifier.size(23.dp))
                }
                DropdownMenu(expanded = more, onDismissRequest = { more = false }) {
                    if (compact) DropdownMenuItem(text = { Text("新建会话") },
                        onClick = { more = false; onNew() })
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
