package io.hydrion.hub.ui.sidebar

import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateFloat
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
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
import io.hydrion.hub.model.isActive
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

internal fun historySections(history: List<Conversation>, query: String,
    assistantId: String? = null): List<HistorySection> {
    val term = query.trim()
    val zone = ZoneId.systemDefault()
    val (pinned, dated) = history.filter { item ->
        (assistantId == null || item.assistantId == assistantId) && item.title.contains(term, ignoreCase = true)
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
    onRename: (String) -> Unit, onPin: (String) -> Unit, onDelete: (String) -> Unit,
    actions: DrawerActions, onHit: (DrawerHit) -> Unit, onAssistantSettings: (String) -> Unit,
) {
    var search by rememberSaveable { mutableStateOf(DrawerSearchState()) }
    val (query, global, submitted) = search
    var showHistory by rememberSaveable { mutableStateOf(false) }
    var showAssistants by rememberSaveable { mutableStateOf(false) }
    var editingId by rememberSaveable { mutableStateOf<String?>(null) }
    assistants.find { it.id == editingId }?.let { editing ->
        AssistantNameDialog(editing, { editingId = null }) { onRenameAssistant(editing.id, it) }
    }
    var assistantMenu by rememberSaveable { mutableStateOf<String?>(null) }
    var groupId by rememberSaveable { mutableStateOf<String?>(null) }
    var removeId by rememberSaveable { mutableStateOf<String?>(null) }
    assistants.find { it.id == assistantMenu }?.let { item ->
        val copyName = stringResource(R.string.drawer_copy_name, item.name).take(64)
        DrawerActionSheet(item.name, { assistantMenu = null }) {
            DrawerAction(stringResource(R.string.assistant_detail), Icons.Outlined.Settings) {
                assistantMenu = null; onAssistantSettings(item.id)
            }
            DrawerAction(stringResource(R.string.assistant_rename), Icons.Outlined.Edit) { assistantMenu = null; editingId = item.id }
            DrawerAction(stringResource(R.string.drawer_duplicate), Icons.Outlined.ContentCopy) {
                assistantMenu = null
                actions.copyAssistant(item.id, copyName)
            }
            DrawerAction(stringResource(R.string.drawer_group), Icons.Outlined.FolderOpen) {
                assistantMenu = null; groupId = item.id
            }
            if (item.id != LocalAssistant.Default.id) {
                DrawerAction(stringResource(R.string.drawer_delete), Icons.Outlined.DeleteOutline, enabled = actions.canRemoveAssistant(item.id), destructive = true) {
                    assistantMenu = null; removeId = item.id
                }
            }
        }
    }
    assistants.find { it.id == groupId }?.let { item ->
        AssistantGroupDialog(item, assistants.mapNotNull { it.sidebarGroup }.distinct(),
            close = { groupId = null }, save = { actions.groupAssistant(item.id, it); groupId = null })
    }
    assistants.find { it.id == removeId }?.let { item ->
        ConfirmDrawerDelete(stringResource(R.string.drawer_delete_assistant, item.name), { removeId = null }) {
            actions.removeAssistant(item.id); removeId = null
        }
    }
    val busyOwners = remember(history) {
        history.filter { row -> row.messages.any { it.status.isActive } }.mapTo(mutableSetOf()) { it.assistantId }
    }
    val currentAssistant = assistants.first { it.id == assistantId }
    val focus = LocalFocusManager.current
    val filter = if (global) submitted else query
    val sections = remember(history, filter, global, assistantId) {
        if (global) emptyList() else historySections(history, query, assistantId)
    }
    val hitResult: List<DrawerHit>? = key(global, submitted) {
        val term = submitted
        if (!global || term == null) emptyList() else {
            val results by produceState<List<DrawerHit>?>(null, history) {
                value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { globalDrawerHits(history, term) }
            }
            results
        }
    }
    val hits = hitResult.orEmpty()
    val tokens = remember(submitted) { submitted.orEmpty().trim().split(Regex("\\s+")) }
    val visibleIds = remember(sections) { sections.flatMap { it.rows }.mapTo(linkedSetOf()) { it.id } }
    var selected by rememberSaveable(assistantId) { mutableStateOf<ArrayList<String>?>(null) }
    var menuId by rememberSaveable { mutableStateOf<String?>(null) }
    var moveIds by rememberSaveable { mutableStateOf(arrayListOf<String>()) }
    var deleteIds by rememberSaveable { mutableStateOf(arrayListOf<String>()) }
    val selecting = selected != null
    val selectedIds = remember(selected, visibleIds) { selected.orEmpty().intersect(visibleIds) }
    val selectedRows = remember(selectedIds, history) { if (selectedIds.isEmpty()) emptyList() else history.filter { it.id in selectedIds } }
    val allPinned = selectedRows.isNotEmpty() && selectedRows.all { it.pinned }
    fun toggle(id: String) { selected = ArrayList(if (id in selectedIds) selectedIds - id else selectedIds + id) }
    LaunchedEffect(history.isEmpty()) {
        if (history.isEmpty()) { selected = null; menuId = null; moveIds = arrayListOf(); deleteIds = arrayListOf() }
    }
    BackHandler(selecting && history.isNotEmpty()) { selected = null }
    history.find { it.id == menuId }?.let { item ->
        val copyName = stringResource(R.string.drawer_copy_name, item.title)
        ChatActionsSheet(item, assistants.size > 1, { menuId = null },
            select = { menuId = null; focus.clearFocus(); showAssistants = false; selected = arrayListOf(item.id) },
            rename = { menuId = null; onRename(item.id) }, pin = { menuId = null; onPin(item.id) },
            duplicate = { menuId = null; actions.duplicate(item.id, copyName) },
            move = { menuId = null; moveIds = arrayListOf(item.id) }, delete = { menuId = null; onDelete(item.id) })
    }
    if (moveIds.isNotEmpty()) {
        val rows = history.filter { it.id in moveIds }
        DrawerActionSheet(stringResource(R.string.drawer_move), { moveIds = arrayListOf() }) {
            assistants.filter { dest -> rows.any { it.assistantId != dest.id } }.forEach { dest ->
                DrawerAction(dest.name, Icons.Outlined.PersonOutline) {
                    actions.move(rows.mapTo(mutableSetOf()) { it.id }, dest.id)
                    moveIds = arrayListOf(); selected = null
                }
            }
        }
    }
    if (deleteIds.isNotEmpty()) {
        ConfirmDrawerDelete(stringResource(R.string.drawer_delete_selected, deleteIds.size), { deleteIds = arrayListOf() }) {
            actions.delete(deleteIds.toSet()); deleteIds = arrayListOf(); selected = null
        }
    }
    val scroll = rememberLazyListState()
    LaunchedEffect(filter, global, assistantId) { scroll.scrollToItem(0) }
    LaunchedEffect(showAssistants) { if (showAssistants) scroll.animateScrollToItem(0) }
    val easeOut = CubicBezierEasing(0.215f, 0.61f, 0.355f, 1f)
    val rotation by animateFloatAsState(if (showAssistants) 180f else 0f, tween(350, easing = easeOut),
        label = "Assistant disclosure")
    val assistantState = stringResource(if (showAssistants) R.string.drawer_expanded else R.string.drawer_collapsed)
    if (showHistory) {
        DrawerHistoryPage(history, currentId, currentAssistant, assistants.size > 1, actions, onBack = { showHistory = false },
            onOpen = { showHistory = false; onOpen(it) }, onRename = { showHistory = false; onRename(it) },
            onPin = onPin)
    }
    val colors = MaterialTheme.colorScheme
    ModalDrawerSheet(
        modifier = Modifier.width(width).testTag("drawer-sheet"), drawerShape = RectangleShape,
        drawerContainerColor = containerColor, drawerContentColor = colors.onSurface,
    ) {
        Column(Modifier.fillMaxSize().imePadding()) {
            if (selecting) SelectionHeader(selectedIds.size, selectedIds.size == visibleIds.size,
                cancel = { selected = null }, toggleAll = {
                    selected = if (selectedIds.size == visibleIds.size) arrayListOf() else ArrayList(visibleIds)
                }) else {
                Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 12.dp, top = 10.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    DrawerSearch(query, onQuery = { search = search.edit(it) }, modifier = Modifier.weight(1f),
                        global = global, onToggleScope = { search = search.toggleScope() }, onSubmit = {
                            search = search.submit()
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
                            onLongClick = { assistantMenu = assistantId }, onClick = { showAssistants = !showAssistants })
                        .semantics { stateDescription = assistantState }.heightIn(min = 48.dp)
                        .padding(start = 4.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        AssistantAvatar(currentAssistant)
                        Text(currentAssistant.name, Modifier.padding(start = 16.dp).weight(1f),
                            fontSize = 15.sp, fontWeight = FontWeight.Normal, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (assistantId in busyOwners) DrawerLoadingDot()
                        Spacer(Modifier.width(8.dp))
                        Icon(painterResource(R.drawable.lucide_chevron_right), stringResource(R.string.drawer_assistants),
                            Modifier.size(18.dp).rotate(if (LocalLayoutDirection.current == LayoutDirection.Ltr) 90f + rotation else -90f + rotation), tint = colors.onSurface.copy(alpha = 0.7f))
                    }
                    IconButton(onNew, Modifier.testTag("drawer-new")) {
                        Icon(Icons.Outlined.Edit, stringResource(R.string.drawer_new), Modifier.size(20.dp))
                    }
                }
            }
            LazyColumn(Modifier.fillMaxWidth().weight(1f), state = scroll,
                contentPadding = PaddingValues(start = 10.dp, end = 10.dp, top = 4.dp, bottom = 12.dp)) {
                item(key = "assistants") {
                    val sizeSpec = tween<androidx.compose.ui.unit.IntSize>(260,
                        easing = CubicBezierEasing(0.645f, 0.045f, 0.355f, 1f))
                    androidx.compose.animation.AnimatedVisibility(showAssistants && !selecting && !global,
                        enter = expandVertically(sizeSpec, expandFrom = Alignment.Top) + fadeIn(tween(220, easing = easeOut)),
                        exit = shrinkVertically(sizeSpec, shrinkTowards = Alignment.Top) + fadeOut(tween(220,
                            easing = CubicBezierEasing(0.55f, 0.055f, 0.675f, 0.19f)))) {
                        AssistantGroups(assistants) { assistant ->
                            AssistantRow(assistant, assistant.id == assistantId, showAssistants && assistantsReady,
                                loading = assistant.id in busyOwners,
                                onSelect = { showAssistants = false; onSelectAssistant(assistant.id) },
                                onMenu = { assistantMenu = assistant.id }, onEdit = { onAssistantSettings(assistant.id) })
                        }
                    }
                }
                if (hitResult == null) item {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp))
                } else if (sections.isEmpty() && hits.isEmpty()) item {
                    val empty = when {
                        filter == null -> R.string.drawer_global_submit_hint
                        filter.isBlank() -> R.string.drawer_empty
                        else -> R.string.drawer_no_matches
                    }
                    Text(stringResource(empty),
                        Modifier.padding(14.dp), fontSize = 14.sp, color = colors.onSurfaceVariant)
                }
                if (global && submitted != null && hits.isNotEmpty()) {
                    item { Text(stringResource(R.string.drawer_result_count, hits.size),
                        Modifier.padding(horizontal = 14.dp, vertical = 6.dp), fontSize = 12.sp, color = colors.onSurfaceVariant) }
                    items(hits, key = { it.id }) { hit ->
                        DrawerSearchResult(hit, tokens, selected = hit.id == search.selectedResultId) {
                            search = search.selectResult(hit.id)
                            onHit(hit)
                        }
                    }
                }
                sections.forEach { group ->
                    item(key = "group-${group.key}") { SectionHeading(sectionLabel(group.date)) }
                    itemsIndexed(group.rows, key = { _, it -> it.id }) { index, item ->
                        val pinned = group.date == null
                        val delay = index.coerceAtMost(7) * (if (pinned) 20 else 16)
                        val entrance = remember { MutableTransitionState(false).apply { targetState = true } }
                        val rise = with(androidx.compose.ui.platform.LocalDensity.current) { (if (pinned) 8.dp else 6.dp).roundToPx() }
                        androidx.compose.animation.AnimatedVisibility(entrance, enter = fadeIn(tween(220, delay)) +
                            slideInVertically(tween(if (pinned) 260 else 240, delay, easeOut)) { rise }) {
                            DrawerConversation(item, currentId == item.id, actions.hasDraft(item.id),
                                selected = if (selecting) item.id in selectedIds else null,
                                onClick = { if (selecting) toggle(item.id) else onOpen(item.id) },
                                onLongClick = { if (selecting) toggle(item.id) else menuId = item.id })
                        }
                    }
                    item(key = "gap-${group.key}") { Spacer(Modifier.height(8.dp)) }
                }
            }
            if (selecting) SelectionFooter(allPinned,
                movable = selectedRows.isNotEmpty() && assistants.size > 1 && selectedRows.none { row -> row.messages.any { it.status.isActive } },
                enabled = selectedRows.isNotEmpty(), pin = { actions.pin(selectedIds, !allPinned) },
                move = { moveIds = ArrayList(selectedIds) }, delete = { deleteIds = ArrayList(selectedIds) })
            else DrawerFooter(width, onNew, onSettings)
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
internal fun SectionHeading(label: String) {
    Text(label, Modifier.padding(start = 14.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
        color = MaterialTheme.colorScheme.primary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
internal fun DrawerConversation(item: Conversation, current: Boolean, hasDraft: Boolean, selected: Boolean?,
    trailingAction: (@Composable () -> Unit)? = null, onClick: () -> Unit, onLongClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val background = when {
        selected == true -> colors.primary.copy(alpha = .16f)
        selected == null && current -> colors.primary.copy(alpha = .12f)
        else -> colors.surface
    }
    Row(Modifier.fillMaxWidth().padding(bottom = 4.dp).clip(RoundedCornerShape(16.dp)).background(background)
        .combinedClickable(onLongClickLabel = stringResource(R.string.drawer_manage, item.title),
            onLongClick = onLongClick, onClick = onClick)
        .semantics { this.selected = selected ?: current; contentDescription = item.mode.name }
        .heightIn(min = 44.dp).padding(start = 14.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically) {
        AnimatedVisibility(visible = selected != null, enter = expandHorizontally(tween(240)) + fadeIn(),
            exit = shrinkHorizontally(tween(240)) + fadeOut()) {
            Box(Modifier.width(28.dp), contentAlignment = Alignment.CenterStart) {
                Checkbox(selected == true, onCheckedChange = null, modifier = Modifier.size(20.dp))
            }
        }
        Text(item.title, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 15.sp)
        if (hasDraft) Text(stringResource(R.string.drawer_draft), Modifier.padding(start = 6.dp),
            fontSize = 11.sp, color = colors.primary)
        if (item.messages.any { it.status.isActive }) DrawerLoadingDot()
        trailingAction?.invoke()
    }
}

@Composable
internal fun DrawerLoadingDot() {
    val generating = stringResource(R.string.drawer_generating)
    val transition = androidx.compose.animation.core.rememberInfiniteTransition(label = "Generating")
    val opacity = transition.animateFloat(1f, .4f,
        androidx.compose.animation.core.infiniteRepeatable(tween(900), androidx.compose.animation.core.RepeatMode.Reverse), label = "Pulse")
    Spacer(Modifier.padding(start = 8.dp).size(9.dp).graphicsLayer { alpha = opacity.value }
        .background(MaterialTheme.colorScheme.primary, CircleShape).semantics { contentDescription = generating })
}
