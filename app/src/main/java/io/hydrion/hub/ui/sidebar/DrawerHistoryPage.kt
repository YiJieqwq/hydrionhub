package io.hydrion.hub.ui.sidebar

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Search
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
import io.hydrion.hub.model.Conversation

@Composable
internal fun DrawerHistoryPage(
    history: List<Conversation>, currentId: String, onBack: () -> Unit, onOpen: (String) -> Unit,
    onRename: (String) -> Unit, onPin: (String) -> Unit, onDelete: (String) -> Unit,
) {
    var searching by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    val sections = remember(history, query) { historySections(history, query) }
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
                    IconButton({ searching = !searching; query = "" }) {
                        Icon(if (searching) Icons.Outlined.Close else Icons.Outlined.Search,
                            stringResource(if (searching) R.string.settings_end_search else R.string.drawer_search))
                    }
                }
                if (searching) DrawerSearch(query, { query = it }, Modifier.fillMaxWidth().padding(horizontal = 12.dp))
                LazyColumn(state = scroll, contentPadding = PaddingValues(12.dp)) {
                    if (sections.isEmpty()) item {
                        Text(stringResource(if (query.isBlank()) R.string.drawer_empty else R.string.drawer_no_matches))
                    }
                    sections.forEach { section ->
                        item(key = "group-${section.key}") {
                            Text(sectionLabel(section.date), Modifier.padding(8.dp), style = MaterialTheme.typography.labelMedium)
                        }
                        items(section.rows, key = { it.id }) { item ->
                            DrawerConversation(item, item.id == currentId, onOpen, onRename, onPin, onDelete)
                        }
                    }
                }
            }
        }
    }
}
