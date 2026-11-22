package io.hydrion.hub.ui.sidebar

import androidx.compose.foundation.clickable
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.hydrion.hub.R
import androidx.compose.material.icons.outlined.*
import io.hydrion.hub.model.LocalAssistant
import io.hydrion.hub.model.Conversation
import io.hydrion.hub.model.isActive

internal fun List<HistorySection>.visibleConversation(id: String): Conversation? =
    firstNotNullOfOrNull { section -> section.rows.find { it.id == id } }

@Composable
internal fun DrawerHistoryPage(
    history: List<Conversation>, currentId: String, assistant: LocalAssistant, multiple: Boolean, actions: DrawerActions,
    onBack: () -> Unit, onOpen: (String) -> Unit,
    onRename: (String) -> Unit, onPin: (String) -> Unit,
    assistants: List<LocalAssistant> = listOf(assistant),
) {
    var searching by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var allOwners by rememberSaveable { mutableStateOf(false) }
    var showArchived by rememberSaveable { mutableStateOf(false) }
    val scoped = remember(history, assistant.id, allOwners) {
        if (allOwners) history else history.filter { it.assistantId == assistant.id }
    }
    val sections = remember(scoped, query, showArchived) { historySections(scoped, query, archived = showArchived) }
    var clearIds by rememberSaveable(assistant.id, allOwners, showArchived) { mutableStateOf(arrayListOf<String>()) }
    var menuId by rememberSaveable(assistant.id, allOwners, query, showArchived) { mutableStateOf<String?>(null) }
    var pendingDeleteId by rememberSaveable(assistant.id, allOwners, query, showArchived) { mutableStateOf<String?>(null) }
    var archiveTarget by rememberSaveable(assistant.id, allOwners, query, showArchived) { mutableStateOf<ArchiveTarget?>(null) }
    val scroll = rememberLazyListState()
    fun togglePin(id: String) {
        // Keep the first section visible when its header appears or disappears.
        if (!scroll.canScrollBackward) scroll.requestScrollToItem(0)
        onPin(id)
    }
    LaunchedEffect(query, showArchived, assistant.id, allOwners) { scroll.scrollToItem(0) }
    Dialog(onDismissRequest = onBack, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize().testTag("drawer-history-page")) {
            Column(Modifier.safeDrawingPadding().imePadding()) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.settings_back))
                    }
                    Text(stringResource(if (showArchived) R.string.history_archived else R.string.drawer_history), Modifier.weight(1f),
                        style = MaterialTheme.typography.titleLarge)
                    IconToggleButton(showArchived, { showArchived = it }, Modifier.testTag("history-archive-filter")) {
                        Icon(Icons.Outlined.Archive,
                            stringResource(if (showArchived) R.string.history_show_active else R.string.history_archive_filter))
                    }
                    if (multiple) IconToggleButton(allOwners, { allOwners = it }) {
                        Icon(if (allOwners) Icons.Outlined.Groups else Icons.Outlined.PersonOutline,
                            stringResource(if (allOwners) R.string.drawer_current_owner else R.string.drawer_all_owners))
                    }
                    IconButton({ searching = !searching; query = "" }) {
                        Icon(if (searching) Icons.Outlined.Close else Icons.Outlined.Search,
                            stringResource(if (searching) R.string.settings_end_search else R.string.drawer_search))
                    }
                    if (!allOwners && !showArchived) IconButton(enabled = scoped.any { !it.archived && !it.pinned }, onClick = {
                        clearIds = ArrayList(scoped.filter { !it.archived && !it.pinned }.map { it.id })
                    }) { Icon(Icons.Outlined.DeleteOutline, stringResource(R.string.drawer_clear_history)) }
                }
                if (searching) DrawerSearch(query, { query = it }, Modifier.fillMaxWidth().padding(horizontal = 12.dp))
                LazyColumn(state = scroll, contentPadding = PaddingValues(12.dp)) {
                    if (sections.isEmpty()) item {
                        Box(Modifier.fillParentMaxSize(), contentAlignment = Alignment.Center) {
                            Text(
                                text = stringResource(
                                    when {
                                        query.isNotBlank() -> R.string.drawer_no_matches
                                        showArchived -> R.string.history_archived_empty
                                        else -> R.string.drawer_empty
                                    }
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
                            if (showArchived) {
                                ArchivedConversationCard(item, actions.hasDraft(item.id),
                                    ownerName = if (allOwners) assistants.find { it.id == item.assistantId }?.name else null,
                                    onRestore = { archiveTarget = ArchiveTarget(item) })
                            } else HistoryConversationCard(
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
            val eligible = scoped.filter { !it.archived && !it.pinned && it.assistantId == assistant.id }.map { it.id }.toSet()
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
                DrawerAction(stringResource(R.string.history_archive), Icons.Outlined.Archive,
                    enabled = item.messages.none { it.status.isActive }) {
                    menuId = null
                    archiveTarget = ArchiveTarget(item)
                }
                DrawerAction(stringResource(R.string.drawer_delete), Icons.Outlined.DeleteOutline, destructive = true) {
                    menuId = null
                    pendingDeleteId = item.id
                }
            }
        }
    }
    ConfirmHistoryArchive(archiveTarget, sections, onDismiss = { archiveTarget = null }, onSetArchived = actions.setArchived)
}

internal data class ArchiveTarget(val id: String, val title: String, val owner: String, val archived: Boolean) : java.io.Serializable {
    constructor(item: Conversation) : this(item.id, item.title, item.assistantId, item.archived)

    fun matches(item: Conversation) = item.id == id && item.title == title && item.assistantId == owner &&
        item.archived == archived && (archived || item.messages.none { it.status.isActive })
}

@Composable
internal fun ConfirmHistoryArchive(
    target: ArchiveTarget?, sections: List<HistorySection>, onDismiss: () -> Unit,
    onSetArchived: (String, Boolean) -> Unit,
) {
    if (target == null) return
    val item = sections.visibleConversation(target.id)
    if (item == null || !target.matches(item)) {
        LaunchedEffect(target) { onDismiss() }
        return
    }
    val archive = !target.archived
    val verb = stringResource(if (archive) R.string.history_archive else R.string.history_restore)
    AlertDialog(onDismissRequest = onDismiss, title = { Text(verb) }, text = {
        Text(stringResource(if (archive) R.string.history_confirm_archive else R.string.history_confirm_restore, item.title))
    }, dismissButton = {
        TextButton(onDismiss) { Text(stringResource(R.string.settings_cancel)) }
    }, confirmButton = {
        TextButton(onClick = {
            onDismiss()
            onSetArchived(item.id, archive)
        }, modifier = Modifier.testTag("history-confirm-archive-action")) { Text(verb) }
    })
}

@Composable
private fun ArchivedConversationCard(item: Conversation, hasDraft: Boolean, ownerName: String?, onRestore: () -> Unit) {
    val restore = stringResource(R.string.history_restore)
    Surface(modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
        shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Row(Modifier.fillMaxWidth().testTag("history-row-${item.id}").clickable(onClickLabel = restore, onClick = onRestore)
            .heightIn(min = 48.dp).padding(start = 14.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(item.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
                if (ownerName != null) Text(stringResource(R.string.history_archive_owner, ownerName),
                    maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                if (hasDraft) Text(stringResource(R.string.drawer_draft), style = MaterialTheme.typography.bodySmall)
            }
            TextButton(onClick = onRestore, modifier = Modifier.testTag("history-restore-${item.id}")) { Text(restore) }
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
