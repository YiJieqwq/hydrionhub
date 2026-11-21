package io.hydrion.hub.ui.sidebar

import androidx.compose.animation.*
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.platform.LocalFocusManager
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.hydrion.hub.R
import io.hydrion.hub.ui.theme.HubStyle
import io.hydrion.hub.model.Conversation
import io.hydrion.hub.ui.profile.LocalProfileStore
import io.hydrion.hub.ui.profile.ProfileAvatar
import io.hydrion.hub.model.LocalAssistant
import androidx.compose.ui.res.painterResource
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

// A null date identifies the pinned section. Dates provide stable keys across language changes.
internal data class HistorySection(val date: LocalDate?, val rows: List<Conversation>) {
    val key = date?.toString() ?: "pinned"
}

internal fun historySections(history: List<Conversation>, query: String, global: Boolean = false,
    assistantId: String? = null): List<HistorySection> {
    val term = query.trim()
    val tokens = if (global) term.split(Regex("\\s+")) else emptyList()
    val zone = ZoneId.systemDefault()
    val (pinned, dated) = history.filter { item ->
        if (!global && assistantId != null && item.assistantId != assistantId) return@filter false
        term.isEmpty() || item.title.contains(term, ignoreCase = true) || (global && tokens.all { word ->
            item.title.contains(word, ignoreCase = true) || item.messages.any { message ->
                message.text.contains(word, ignoreCase = true) ||
                    (message.imageKeys.isNotEmpty() && message.prompt.contains(word, ignoreCase = true))
            }
        })
    }.sortedByDescending { it.updatedAt }.partition { it.pinned }
    return buildList {
        if (pinned.isNotEmpty()) add(HistorySection(null, pinned))
        dated.groupBy { Instant.ofEpochMilli(it.updatedAt).atZone(zone).toLocalDate() }
            .forEach { (date, rows) -> add(HistorySection(date, rows)) }
    }
}

@Composable
internal fun sectionLabel(date: LocalDate?): String {
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

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
internal fun ConversationDrawer(
    width: Dp, containerColor: Color, history: List<Conversation>, currentId: String,
    assistants: List<LocalAssistant>, assistantId: String, onSelectAssistant: (String) -> Unit,
    onRenameAssistant: (String, String) -> Boolean, assistantsReady: Boolean,
    onNew: () -> Unit, onOpen: (String) -> Unit, onSettings: () -> Unit,
    onRename: (String) -> Unit, onPin: (String) -> Unit, onDelete: (String) -> Unit
) {
    var query by rememberSaveable { mutableStateOf("") }
    var global by rememberSaveable { mutableStateOf(false) }
    var submitted by rememberSaveable { mutableStateOf<String?>(null) }
    var showHistory by rememberSaveable { mutableStateOf(false) }
    var showAssistants by rememberSaveable { mutableStateOf(false) }
    var editingId by rememberSaveable { mutableStateOf<String?>(null) }
    assistants.find { it.id == editingId }?.let { editing ->
        AssistantNameDialog(editing, { editingId = null }) { onRenameAssistant(editing.id, it) }
    }
    val currentAssistant = assistants.first { it.id == assistantId }
    val focus = LocalFocusManager.current
    val filter = if (global) submitted else query
    val sections = remember(history, filter, global, assistantId) {
        if (filter == null) emptyList() else historySections(history, filter, global, assistantId)
    }
    var collapsed by rememberSaveable(filter, global) { mutableStateOf(arrayListOf<String>()) }
    val scroll = rememberLazyListState()
    LaunchedEffect(filter, global, assistantId) { scroll.scrollToItem(0) }
    LaunchedEffect(showAssistants) { if (showAssistants) scroll.animateScrollToItem(0) }
    val easeOut = CubicBezierEasing(0.215f, 0.61f, 0.355f, 1f)
    val rotation by animateFloatAsState(if (showAssistants) 180f else 0f, tween(350, easing = easeOut),
        label = "Assistant disclosure")
    val assistantState = stringResource(if (showAssistants) R.string.drawer_expanded else R.string.drawer_collapsed)
    val toggleScope = {
        global = !global
        query = ""
        submitted = null
    }
    if (showHistory) {
        DrawerHistoryPage(history, currentId, onBack = { showHistory = false },
            onOpen = { showHistory = false; onOpen(it) }, onRename = { showHistory = false; onRename(it) },
            onPin = onPin, onDelete = { showHistory = false; onDelete(it) })
    }
    val colors = MaterialTheme.colorScheme
    ModalDrawerSheet(
        modifier = Modifier.width(width).testTag("drawer-sheet"), drawerShape = RectangleShape,
        drawerContainerColor = containerColor, drawerContentColor = colors.onSurface,
    ) {
        Column(Modifier.fillMaxSize().imePadding()) {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 12.dp, top = 10.dp),
                verticalAlignment = Alignment.CenterVertically) {
                DrawerSearch(query, onQuery = { query = it; submitted = null }, modifier = Modifier.weight(1f),
                    global = global, onToggleScope = toggleScope, onSubmit = {
                        submitted = query.trim().takeIf { it.isNotEmpty() }
                        focus.clearFocus()
                    })
                IconButton({ focus.clearFocus(); showHistory = true }) {
                    Icon(Icons.Outlined.History, stringResource(R.string.drawer_history), Modifier.size(21.dp))
                }
            }
            Row(Modifier.fillMaxWidth().padding(start = 22.dp, end = 16.dp, top = 2.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.weight(1f).clip(RoundedCornerShape(16.dp))
                    .combinedClickable(enabled = assistantsReady, role = Role.Button,
                        onLongClick = { editingId = assistantId }, onClick = { showAssistants = !showAssistants })
                    .semantics { stateDescription = assistantState }.heightIn(min = 48.dp)
                    .padding(start = 4.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    AssistantAvatar(currentAssistant)
                    Text(currentAssistant.name, Modifier.padding(start = 16.dp).weight(1f),
                        fontSize = 15.sp, fontWeight = FontWeight.Normal, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.width(8.dp))
                    Icon(painterResource(R.drawable.lucide_chevron_right), stringResource(R.string.drawer_assistants),
                        Modifier.size(18.dp).rotate(if (LocalLayoutDirection.current == LayoutDirection.Ltr) 90f + rotation else -90f + rotation), tint = colors.onSurface.copy(alpha = 0.7f))
                }
                IconButton(onNew, Modifier.testTag("drawer-new")) {
                    Icon(Icons.Outlined.Edit, stringResource(R.string.drawer_new), Modifier.size(20.dp))
                }
            }

            LazyColumn(Modifier.fillMaxWidth().weight(1f), state = scroll,
                contentPadding = PaddingValues(start = 10.dp, end = 10.dp, top = 4.dp, bottom = 12.dp)) {
                item(key = "assistants") {
                    val sizeSpec = tween<androidx.compose.ui.unit.IntSize>(260,
                        easing = CubicBezierEasing(0.645f, 0.045f, 0.355f, 1f))
                    androidx.compose.animation.AnimatedVisibility(showAssistants,
                        enter = expandVertically(sizeSpec, expandFrom = Alignment.Top) + fadeIn(tween(220, easing = easeOut)),
                        exit = shrinkVertically(sizeSpec, shrinkTowards = Alignment.Top) + fadeOut(tween(220,
                            easing = CubicBezierEasing(0.55f, 0.055f, 0.675f, 0.19f)))) {
                        Column(Modifier.padding(start = 10.dp, end = 10.dp, top = 6.dp, bottom = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            assistants.forEach { assistant ->
                                key(assistant.id) {
                                    AssistantRow(assistant, assistant.id == assistantId, showAssistants && assistantsReady,
                                        onSelect = { showAssistants = false; onSelectAssistant(assistant.id) },
                                        onEdit = { editingId = assistant.id })
                                }
                            }
                        }
                    }
                }
                if (sections.isEmpty()) item {
                    val empty = when {
                        filter == null -> R.string.drawer_global_submit_hint
                        filter.isBlank() -> R.string.drawer_empty
                        else -> R.string.drawer_no_matches
                    }
                    Text(stringResource(empty),
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
            DrawerFooter(width, onNew, onSettings)
        }
    }
}

@Composable
private fun DrawerFooter(width: Dp, onNew: () -> Unit, onSettings: () -> Unit) {
    val context = LocalContext.current
    val store = remember(context) { LocalProfileStore.shared(context) }
    val profile by store.profile.collectAsState()
    val colors = MaterialTheme.colorScheme
    val newChat = stringResource(R.string.drawer_new)
    val settings = stringResource(R.string.drawer_settings)
    // Make room for both buttons and the gap before using the wider outer margins.
    val chatWidth = 113.dp
    val avatarSize = 48.dp
    val gap = 8.dp
    val compact = width < 32.dp + chatWidth + gap + avatarSize + 25.dp
    Row(Modifier.fillMaxWidth().padding(start = if (compact) 16.dp else 32.dp,
        end = if (compact) 16.dp else 25.dp, top = 6.dp, bottom = 14.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(gap)) {
        Row(Modifier.weight(1f).wrapContentWidth(Alignment.Start).widthIn(min = chatWidth)
            .clip(CircleShape).background(colors.primary)
            .clickable(role = Role.Button, onClickLabel = newChat, onClick = onNew).testTag("drawer-new-chat")
            .heightIn(min = 48.dp).padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Edit, null, Modifier.size(19.dp), tint = colors.onPrimary)
            Text(stringResource(R.string.drawer_chat), color = colors.onPrimary, fontSize = 15.sp,
                fontWeight = FontWeight.Medium, maxLines = 1)
        }
        Box(Modifier.size(avatarSize).border(HubStyle.SettingsOutline, CircleShape).clip(CircleShape).background(colors.surfaceContainerHigh)
            .testTag("drawer-settings").clickable(role = Role.Button, onClick = onSettings)
            .semantics { contentDescription = settings }, contentAlignment = Alignment.Center) {
            ProfileAvatar(profile, Modifier.size(32.dp))
        }
    }
}

@Composable
internal fun DrawerSearch(query: String, onQuery: (String) -> Unit, modifier: Modifier,
    global: Boolean = false, onToggleScope: (() -> Unit)? = null, onSubmit: () -> Unit = {}) {
    val colors = MaterialTheme.colorScheme
    val hint = stringResource(if (global) R.string.drawer_global_search else R.string.drawer_search)
    BasicTextField(value = query, onValueChange = onQuery, singleLine = true,
        modifier = modifier.drawerSearchSwipe(onToggleScope, query.isBlank()).clip(RoundedCornerShape(16.dp)).background(colors.surfaceContainerHigh)
            .heightIn(min = 44.dp).semantics { contentDescription = hint },
        textStyle = MaterialTheme.typography.bodyMedium.copy(color = colors.onSurface),
        keyboardOptions = KeyboardOptions(imeAction = if (global) ImeAction.Search else ImeAction.Default),
        keyboardActions = if (global) KeyboardActions(onSearch = { onSubmit() }) else KeyboardActions.Default,
        cursorBrush = SolidColor(colors.primary),
        decorationBox = { field ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (onToggleScope != null) {
                    IconButton(onToggleScope, Modifier.size(44.dp)) {
                        Icon(if (global) Icons.Outlined.Storage else Icons.Outlined.ChatBubbleOutline,
                            stringResource(if (global) R.string.drawer_switch_topics else R.string.drawer_switch_global),
                            Modifier.size(18.dp), tint = colors.onSurfaceVariant)
                    }
                } else Icon(Icons.Outlined.Search, null, Modifier.padding(start = 12.dp).size(18.dp), tint = colors.onSurfaceVariant)
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
internal fun DrawerConversation(
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
