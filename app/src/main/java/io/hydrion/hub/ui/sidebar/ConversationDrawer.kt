package io.hydrion.hub.ui.sidebar

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.hydrion.hub.model.Conversation
import io.hydrion.hub.model.Mode
import io.hydrion.hub.ui.theme.capsuleEdge
import io.hydrion.hub.ui.theme.IonMark
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private fun day(time: Long): LocalDate = Instant.ofEpochMilli(time).atZone(ZoneId.systemDefault()).toLocalDate()

private fun dateLabel(date: LocalDate): String {
    val today = LocalDate.now()
    return when (date) {
        today -> "今天"
        today.minusDays(1) -> "昨天"
        else -> date.format(DateTimeFormatter.ofPattern(if (today.year == date.year) "M月d日" else "yyyy年M月d日"))
    }
}

@Composable
internal fun ConversationDrawer(
    history: List<Conversation>, currentId: String,
    onNew: () -> Unit, onOpen: (String) -> Unit, onSettings: () -> Unit,
    onRename: (String) -> Unit, onPin: (String) -> Unit, onDelete: (String) -> Unit
) {
    var query by rememberSaveable { mutableStateOf("") }
    val results = remember(history, query) {
        val term = query.trim()
        history.filter { c -> term.isEmpty() || c.title.contains(term, true) ||
            c.messages.any { it.text.contains(term, true) || it.prompt.contains(term, true) }
        }.sortedByDescending { it.updatedAt }
    }
    val pinned = results.filter { it.pinned }
    val groups = results.filterNot { it.pinned }.groupBy { day(it.updatedAt) }
    val colors = MaterialTheme.colorScheme
    ModalDrawerSheet(
        modifier = Modifier.width((LocalConfiguration.current.screenWidthDp.dp - 44.dp).coerceIn(220.dp, 348.dp)),
        drawerContainerColor = colors.surfaceContainerLow,
        drawerShape = MaterialTheme.shapes.large.copy(topStart = androidx.compose.foundation.shape.CornerSize(0.dp),
            bottomStart = androidx.compose.foundation.shape.CornerSize(0.dp))
    ) {
        Column(Modifier.fillMaxHeight().padding(horizontal = 14.dp)) {
            Row(Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                IonMark(40.dp)
                Text("HydrionHub", Modifier.weight(1f).padding(start = 10.dp), style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Medium)
                IconButton(onClick = onNew) { Icon(Icons.Outlined.Edit, "新建会话") }
            }
            Row(
                Modifier.fillMaxWidth().capsuleEdge().clip(CircleShape).background(colors.surfaceContainerHigh).heightIn(min = 44.dp)
                    .padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Outlined.Search, null, Modifier.size(19.dp), tint = colors.onSurfaceVariant)
                BasicTextField(
                    value = query, onValueChange = { query = it }, singleLine = true,
                    modifier = Modifier.weight(1f).padding(horizontal = 8.dp).semantics { contentDescription = "搜索会话" },
                    cursorBrush = SolidColor(colors.primary), textStyle = MaterialTheme.typography.bodyMedium.copy(color = colors.onSurface),
                    decorationBox = { inner -> Box { if (query.isEmpty()) Text("搜索会话", color = colors.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium); inner() } }
                )
                if (query.isNotEmpty()) IconButton(onClick = { query = "" }, modifier = Modifier.size(34.dp)) {
                    Icon(Icons.Outlined.Close, "清除搜索", Modifier.size(17.dp))
                }
            }
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(vertical = 12.dp)) {
                if (results.isEmpty()) {
                    item { Text(if (query.isBlank()) "暂无历史会话" else "没有匹配的会话",
                        Modifier.padding(horizontal = 10.dp, vertical = 18.dp),
                        style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant) }
                }
                if (pinned.isNotEmpty()) {
                    item(key = "pinned") { GroupLabel("置顶会话") }
                    items(pinned, key = { it.id }) { conversation ->
                        DrawerRow(conversation, currentId, onOpen, onRename, onPin, onDelete)
                    }
                }
                groups.forEach { (date, conversations) ->
                    item(key = "date-$date") { GroupLabel(dateLabel(date)) }
                    items(conversations, key = { it.id }) { conversation ->
                        DrawerRow(conversation, currentId, onOpen, onRename, onPin, onDelete)
                    }
                }
            }
            HorizontalDivider(color = colors.outlineVariant)
            Row(Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).clickable(onClick = onSettings)
                .heightIn(min = 58.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Settings, null, Modifier.size(20.dp))
                Text("设置", Modifier.weight(1f).padding(start = 12.dp))
                Icon(Icons.Outlined.ChevronRight, null, Modifier.size(18.dp), tint = colors.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun GroupLabel(label: String) {
    Text(label, Modifier.padding(start = 10.dp, top = 17.dp, bottom = 6.dp),
        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun DrawerRow(
    item: Conversation, currentId: String, onOpen: (String) -> Unit,
    onRename: (String) -> Unit, onPin: (String) -> Unit, onDelete: (String) -> Unit
) {
    var menu by remember { mutableStateOf(false) }
    val colors = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small)
        .background(if (item.id == currentId) colors.surfaceContainerHigh else colors.surfaceContainerLow)
        .clickable { onOpen(item.id) }.padding(start = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(if (item.mode == Mode.Chat) Icons.Outlined.ChatBubbleOutline else Icons.Outlined.Image,
            item.mode.name, Modifier.size(18.dp), tint = colors.onSurfaceVariant)
        Text(item.title, Modifier.weight(1f).padding(start = 10.dp), maxLines = 1,
            overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
        Box {
            IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreHoriz, "管理 ${item.title}", Modifier.size(19.dp)) }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text(if (item.pinned) "取消置顶" else "置顶") },
                    onClick = { menu = false; onPin(item.id) })
                DropdownMenuItem(text = { Text("重命名") }, onClick = { menu = false; onRename(item.id) })
                DropdownMenuItem(text = { Text("删除") }, onClick = { menu = false; onDelete(item.id) })
            }
        }
    }
}
