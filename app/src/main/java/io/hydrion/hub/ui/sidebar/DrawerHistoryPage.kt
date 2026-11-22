package io.hydrion.hub.ui.sidebar

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.hydrion.hub.R
import androidx.compose.material.icons.outlined.*
import io.hydrion.hub.model.LocalAssistant
import io.hydrion.hub.model.Conversation

internal fun List<HistorySection>.visibleConversation(id: String): Conversation? =
    firstNotNullOfOrNull { section -> section.rows.find { it.id == id } }

@Composable
internal fun DrawerHistoryPage(
    history: List<Conversation>, currentId: String, assistant: LocalAssistant, multiple: Boolean, actions: DrawerActions,
    onBack: () -> Unit, onOpen: (String) -> Unit,
    onRename: (String) -> Unit, onPin: (String) -> Unit,
) {
    var searching by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var allOwners by rememberSaveable { mutableStateOf(false) }
    val scoped = remember(history, assistant.id, allOwners) {
        if (allOwners) history else history.filter { it.assistantId == assistant.id }
    }
    val sections = remember(scoped, query) { historySections(scoped, query) }
    var clearIds by rememberSaveable { mutableStateOf(arrayListOf<String>()) }
    var menuId by rememberSaveable(assistant.id, allOwners, query) { mutableStateOf<String?>(null) }
    var pendingDeleteId by rememberSaveable(assistant.id, allOwners, query) { mutableStateOf<String?>(null) }
    val scroll = rememberLazyListState()
    fun togglePin(id: String) {
        // Keep the first section visible when its header appears or disappears.
        if (!scroll.canScrollBackward) scroll.requestScrollToItem(0)
        onPin(id)
    }
    LaunchedEffect(query) { scroll.scrollToItem(0) }
    Dialog(onDismissRequest = onBack, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize().testTag("drawer-history-page")) {
            Column(Modifier.safeDrawingPadding().imePadding()) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.settings_back))
                    }
                    Text(stringResource(R.string.drawer_history), Modifier.weight(1f),
                        style = MaterialTheme.typography.titleLarge)
                    if (multiple) IconToggleButton(allOwners, { allOwners = it }) {
                        Icon(if (allOwners) Icons.Outlined.Groups else Icons.Outlined.PersonOutline,
                            stringResource(if (allOwners) R.string.drawer_current_owner else R.string.drawer_all_owners))
                    }
                    IconButton({ searching = !searching; query = "" }) {
                        Icon(if (searching) Icons.Outlined.Close else Icons.Outlined.Search,
                            stringResource(if (searching) R.string.settings_end_search else R.string.drawer_search))
                    }
                    if (!allOwners) IconButton(enabled = scoped.any { !it.pinned }, onClick = {
                        clearIds = ArrayList(scoped.filterNot { it.pinned }.map { it.id })
                    }) { Icon(Icons.Outlined.DeleteOutline, stringResource(R.string.drawer_clear_history)) }
                }
                if (searching) DrawerSearch(query, { query = it }, Modifier.fillMaxWidth().padding(horizontal = 12.dp))
                LazyColumn(state = scroll, contentPadding = PaddingValues(12.dp)) {
                    if (sections.isEmpty()) item {
                        Box(Modifier.fillParentMaxSize(), contentAlignment = Alignment.Center) {
                            Text(
                                text = stringResource(
                                    if (query.isBlank()) R.string.drawer_empty else R.string.drawer_no_matches
                                ),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                            )
                        }
                    }
                    sections.forEach { section ->
                        item(key = "group-${section.key}") {
                            SectionHeading(sectionLabel(section.date))
                        }
                        items(section.rows, key = { it.id }) { item ->
                            HistoryConversationCard(
                                item, item.id == currentId, actions.hasDraft(item.id),
                                onOpen = onOpen, onManage = { menuId = it },
                                onPin = ::togglePin, onRequestDelete = { pendingDeleteId = it },
                            )
                        }
                    }
                }
            }
        }
    }
    if (clearIds.isNotEmpty()) {
        ConfirmDrawerDelete(stringResource(R.string.drawer_confirm_clear_history, clearIds.size, assistant.name),
            { clearIds = arrayListOf() }) {
            val eligible = scoped.filter { !it.pinned && it.assistantId == assistant.id }.map { it.id }.toSet()
            actions.delete(clearIds.intersect(eligible)); clearIds = arrayListOf()
        }
    }
    val actionId = pendingDeleteId ?: menuId
    if (actionId != null) {
        val item = sections.visibleConversation(actionId)
        when {
            item == null -> LaunchedEffect(actionId) {
                pendingDeleteId = null
                menuId = null
            }
            pendingDeleteId != null -> ConfirmDrawerDelete(
                stringResource(R.string.drawer_delete_one, item.title), { pendingDeleteId = null },
            ) {
                if (pendingDeleteId == item.id) {
                    pendingDeleteId = null
                    actions.delete(setOf(item.id))
                }
            }
            else -> DrawerActionSheet(item.title, { menuId = null }) {
                DrawerAction(stringResource(R.string.drawer_rename), Icons.Outlined.Edit) {
                    menuId = null
                    onRename(item.id)
                }
                DrawerAction(stringResource(if (item.pinned) R.string.drawer_unpin else R.string.drawer_pin), Icons.Outlined.PushPin) {
                    menuId = null
                    togglePin(item.id)
                }
                DrawerAction(stringResource(R.string.drawer_delete), Icons.Outlined.DeleteOutline, destructive = true) {
                    menuId = null
                    pendingDeleteId = item.id
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HistoryConversationCard(
    item: Conversation, current: Boolean, hasDraft: Boolean,
    onOpen: (String) -> Unit, onManage: (String) -> Unit,
    onPin: (String) -> Unit, onRequestDelete: (String) -> Unit,
) {
    val requestDelete by rememberUpdatedState(onRequestDelete)
    val swipe = rememberSwipeToDismissBoxState(
        positionalThreshold = { width -> width * 0.4f },
        confirmValueChange = { target ->
            if (target == SwipeToDismissBoxValue.EndToStart) {
                // The confirmation dialog owns deletion. Let the card settle back in place.
                requestDelete(item.id)
                false
            } else true
        },
    )
    val colors = MaterialTheme.colorScheme
    SwipeToDismissBox(
        state = swipe, modifier = Modifier.testTag("history-row-${item.id}"),
        enableDismissFromStartToEnd = false,
        backgroundContent = {
            if (swipe.dismissDirection != SwipeToDismissBoxValue.Settled) {
                Box(
                    Modifier.fillMaxSize().padding(bottom = 4.dp)
                        .background(colors.errorContainer, RoundedCornerShape(16.dp)).padding(horizontal = 16.dp),
                    contentAlignment = Alignment.CenterEnd,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Icon(Icons.Outlined.DeleteOutline, null, tint = colors.onErrorContainer)
                        Text(stringResource(R.string.drawer_delete), color = colors.onErrorContainer)
                    }
                }
            }
        },
    ) {
        Box(Modifier.fillMaxWidth().background(colors.surface)) {
            DrawerConversation(
                item, current, hasDraft, selected = null,
                onClick = { onOpen(item.id) }, onLongClick = { onManage(item.id) },
                trailingAction = {
                    FilterChip(
                        selected = item.pinned, onClick = { onPin(item.id) },
                        modifier = Modifier.padding(start = 6.dp), shape = CircleShape,
                        label = { Text(stringResource(if (item.pinned) R.string.drawer_unpin else R.string.drawer_pin)) },
                        leadingIcon = { Icon(Icons.Outlined.PushPin, null, Modifier.size(FilterChipDefaults.IconSize)) },
                    )
                },
            )
        }
    }
}
