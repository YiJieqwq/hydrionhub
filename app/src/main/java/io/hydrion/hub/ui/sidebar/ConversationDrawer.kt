package io.hydrion.hub.ui.sidebar

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.hydrion.hub.R
import io.hydrion.hub.model.Conversation
import io.hydrion.hub.ui.profile.LocalProfileStore
import io.hydrion.hub.ui.profile.ProfileAvatar
import io.hydrion.hub.ui.theme.IonMark
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

// A null date identifies the pinned section. Dates provide stable keys across language changes.
internal data class HistorySection(val date: LocalDate?, val rows: List<Conversation>) {
    val key = date?.toString() ?: "pinned"
}

internal fun historySections(history: List<Conversation>, query: String): List<HistorySection> {
    val term = query.trim()
    val zone = ZoneId.systemDefault()
    val (pinned, dated) = history.filter { item ->
        term.isEmpty() || item.title.contains(term, ignoreCase = true) || item.messages.any {
            it.text.contains(term, ignoreCase = true) || it.prompt.contains(term, ignoreCase = true)
        }
    }.sortedByDescending { it.updatedAt }.partition { it.pinned }
    return buildList {
        if (pinned.isNotEmpty()) add(HistorySection(null, pinned))
        dated.groupBy { Instant.ofEpochMilli(it.updatedAt).atZone(zone).toLocalDate() }
            .forEach { (date, rows) -> add(HistorySection(date, rows)) }
    }
}

@Composable
private fun sectionLabel(date: LocalDate?): String {
    val today = LocalDate.now()
    return when (date) {
        null -> stringResource(R.string.drawer_pinned)
        today -> stringResource(R.string.drawer_today)
        today.minusDays(1) -> stringResource(R.string.drawer_yesterday)
        else -> {
            val pattern = stringResource(if (today.year == date.year) R.string.drawer_date_short else R.string.drawer_date_full)
            date.format(DateTimeFormatter.ofPattern(pattern, LocalConfiguration.current.locales[0]))
        }
    }
}

@Composable
internal fun ConversationDrawer(
    width: Dp, containerColor: Color, history: List<Conversation>, currentId: String,
    onNew: () -> Unit, onOpen: (String) -> Unit, onSettings: () -> Unit,
    onRename: (String) -> Unit, onPin: (String) -> Unit, onDelete: (String) -> Unit
) {
    var query by rememberSaveable { mutableStateOf("") }
    val sections = remember(history, query) { historySections(history, query) }
    var collapsed by rememberSaveable(query) { mutableStateOf(arrayListOf<String>()) }
    val scroll = rememberLazyListState()
    var historyMenu by remember { mutableStateOf(false) }
    var jumpTo by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(jumpTo) {
        val key = jumpTo ?: return@LaunchedEffect
        val index = sections.indexOfFirst { it.key == key }
        if (index >= 0) {
            val header = sections.take(index).sumOf { 1 + if (it.key in collapsed) 0 else it.rows.size }
            scroll.scrollToItem(header)
        }
        jumpTo = null
    }

    val colors = MaterialTheme.colorScheme
    ModalDrawerSheet(
        modifier = Modifier.width(width).testTag("drawer-sheet"), drawerShape = RectangleShape,
        drawerContainerColor = containerColor, drawerContentColor = colors.onSurface,
    ) {
        Column(Modifier.fillMaxSize().imePadding()) {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 12.dp, top = 4.dp),
                verticalAlignment = Alignment.CenterVertically) {
                DrawerSearch(query, { query = it }, Modifier.weight(1f))
                Box {
                    IconButton({ historyMenu = true }, enabled = sections.isNotEmpty()) {
                        Icon(Icons.Outlined.History, stringResource(R.string.drawer_history), Modifier.size(21.dp))
                    }
                    DropdownMenu(historyMenu, { historyMenu = false }) {
                        sections.forEach { group ->
                            DropdownMenuItem(text = { Text(sectionLabel(group.date)) }, onClick = {
                                historyMenu = false
                                collapsed = ArrayList(collapsed - group.key)
                                jumpTo = group.key
                            })
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(start = 22.dp, end = 16.dp, top = 8.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically) {
                IonMark(32.dp)
                Text("HydrionHub", Modifier.weight(1f).padding(start = 16.dp), fontSize = 15.sp,
                    fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                IconButton(onNew, Modifier.testTag("drawer-new")) {
                    Icon(Icons.Outlined.Edit, stringResource(R.string.drawer_new), Modifier.size(20.dp))
                }
            }

            LazyColumn(Modifier.fillMaxWidth().weight(1f), state = scroll,
                contentPadding = PaddingValues(start = 10.dp, end = 10.dp, top = 4.dp, bottom = 12.dp)) {
                if (sections.isEmpty()) item {
                    Text(stringResource(if (query.isBlank()) R.string.drawer_empty else R.string.drawer_no_matches),
                        Modifier.padding(14.dp), fontSize = 14.sp, color = colors.onSurfaceVariant)
                }
                sections.forEach { group ->
                    val open = group.key !in collapsed
                    item(key = "group-${group.key}") {
                        SectionHeading(sectionLabel(group.date), open) {
                            collapsed = ArrayList(if (open) collapsed + group.key else collapsed - group.key)
                        }
                    }
                    if (open) items(group.rows, key = { it.id }) { item ->
                        DrawerConversation(item, currentId == item.id, onOpen, onRename, onPin, onDelete)
                    }
                }
            }
            DrawerFooter(onNew, onSettings)
        }
    }
}

@Composable
private fun DrawerFooter(onNew: () -> Unit, onSettings: () -> Unit) {
    val context = LocalContext.current
    val store = remember(context) { LocalProfileStore.shared(context) }
    val profile by store.profile.collectAsState()
    val colors = MaterialTheme.colorScheme
    val newChat = stringResource(R.string.drawer_new)
    val settings = stringResource(R.string.drawer_settings)
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Row(Modifier.clip(CircleShape).background(colors.primary)
            .clickable(role = Role.Button, onClickLabel = newChat, onClick = onNew).testTag("drawer-new-chat")
            .heightIn(min = 48.dp).padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Edit, null, Modifier.size(19.dp), tint = colors.onPrimary)
            Text(stringResource(R.string.drawer_chat), color = colors.onPrimary, fontSize = 15.sp,
                fontWeight = FontWeight.Medium, maxLines = 1)
        }
        Box(Modifier.size(48.dp).clip(CircleShape).background(colors.surfaceContainerHigh)
            .testTag("drawer-settings").clickable(role = Role.Button, onClick = onSettings)
            .semantics { contentDescription = settings }, contentAlignment = Alignment.Center) {
            ProfileAvatar(profile, Modifier.size(32.dp))
        }
    }
}

@Composable
private fun DrawerSearch(query: String, onQuery: (String) -> Unit, modifier: Modifier) {
    val colors = MaterialTheme.colorScheme
    val hint = stringResource(R.string.drawer_search)
    BasicTextField(value = query, onValueChange = onQuery, singleLine = true,
        modifier = modifier.clip(RoundedCornerShape(16.dp)).background(colors.surfaceContainerHigh)
            .heightIn(min = 44.dp).semantics { contentDescription = hint },
        textStyle = MaterialTheme.typography.bodyMedium.copy(color = colors.onSurface),
        cursorBrush = SolidColor(colors.primary),
        decorationBox = { field ->
            Row(Modifier.padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Search, null, Modifier.size(18.dp), tint = colors.onSurfaceVariant)
                Box(Modifier.weight(1f).padding(horizontal = 8.dp), contentAlignment = Alignment.CenterStart) {
                    if (query.isEmpty()) Text(hint, Modifier.align(Alignment.Center), color = colors.onSurfaceVariant,
                        fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    field()
                }
                if (query.isNotEmpty()) IconButton({ onQuery("") }, Modifier.size(44.dp)) {
                    Icon(Icons.Outlined.Close, stringResource(R.string.drawer_clear_search), Modifier.size(17.dp))
                } else Spacer(Modifier.width(22.dp))
            }
        })
}

@Composable
private fun SectionHeading(label: String, open: Boolean, toggle: () -> Unit) {
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val angle by animateFloatAsState(if (open) (if (rtl) -90f else 90f) else 0f, label = "section-chevron")
    val state = stringResource(if (open) R.string.drawer_expanded else R.string.drawer_collapsed)
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(role = Role.Button, onClick = toggle)
        .semantics { stateDescription = state }.heightIn(min = 40.dp).padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null, Modifier.size(16.dp).rotate(angle),
            tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(label, Modifier.padding(start = 8.dp), fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold)
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun DrawerConversation(
    item: Conversation, current: Boolean, onOpen: (String) -> Unit,
    onRename: (String) -> Unit, onPin: (String) -> Unit, onDelete: (String) -> Unit
) {
    var menu by remember { mutableStateOf(false) }
    val colors = MaterialTheme.colorScheme
    val manage = stringResource(R.string.drawer_manage, item.title)
    Box(Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
            .background(if (current) colors.primary.copy(alpha = .12f) else Color.Transparent)
            .combinedClickable(onLongClickLabel = manage, onLongClick = { menu = true }, onClick = { onOpen(item.id) })
            .semantics { selected = current; contentDescription = item.mode.name }
            .heightIn(min = 44.dp).padding(start = 14.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(item.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 15.sp)
        }
        DropdownMenu(menu, { menu = false }) {
            DropdownMenuItem(text = { Text(stringResource(if (item.pinned) R.string.drawer_unpin else R.string.drawer_pin)) },
                onClick = { menu = false; onPin(item.id) })
            DropdownMenuItem(text = { Text(stringResource(R.string.drawer_rename)) }, onClick = { menu = false; onRename(item.id) })
            DropdownMenuItem(text = { Text(stringResource(R.string.drawer_delete)) }, onClick = { menu = false; onDelete(item.id) })
        }
    }
}
