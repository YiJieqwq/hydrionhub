package io.hydrion.hub.ui.sidebar

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.hydrion.hub.R
import androidx.compose.material.icons.outlined.*
import io.hydrion.hub.model.LocalAssistant
import io.hydrion.hub.model.Conversation

@Composable
internal fun DrawerHistoryPage(
    history: List<Conversation>, currentId: String, assistant: LocalAssistant, multiple: Boolean, actions: DrawerActions,
    onBack: () -> Unit, onOpen: (String) -> Unit,
    onRename: (String) -> Unit, onPin: (String) -> Unit, onDelete: (String) -> Unit,
) {
    var searching by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var allOwners by rememberSaveable { mutableStateOf(false) }
    val scoped = remember(history, assistant.id, allOwners) {
        if (allOwners) history else history.filter { it.assistantId == assistant.id }
    }
    val sections = remember(scoped, query) { historySections(scoped, query) }
    var clearIds by rememberSaveable { mutableStateOf(arrayListOf<String>()) }
    var menuId by rememberSaveable { mutableStateOf<String?>(null) }
    val scroll = rememberLazyListState()
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
                        Text(stringResource(if (query.isBlank()) R.string.drawer_empty else R.string.drawer_no_matches))
                    }
                    sections.forEach { section ->
                        item(key = "group-${section.key}") {
                            SectionHeading(sectionLabel(section.date))
                        }
                        items(section.rows, key = { it.id }) { item ->
                            DrawerConversation(item, item.id == currentId, actions.hasDraft(item.id), selected = null,
                                onClick = { onOpen(item.id) }, onLongClick = { menuId = item.id })
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
    history.find { it.id == menuId }?.let { item ->
        DrawerActionSheet(item.title, { menuId = null }) {
            DrawerAction(stringResource(R.string.drawer_rename), Icons.Outlined.Edit) { menuId = null; onRename(item.id) }
            DrawerAction(stringResource(if (item.pinned) R.string.drawer_unpin else R.string.drawer_pin), Icons.Outlined.PushPin) {
                menuId = null; onPin(item.id)
            }
            DrawerAction(stringResource(R.string.drawer_delete), Icons.Outlined.DeleteOutline, destructive = true) { menuId = null; onDelete(item.id) }
        }
    }
}
