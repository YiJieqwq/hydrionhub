package io.hydrion.hub.provider

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.hydrion.hub.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RemoteModelPicker(
    provider: Provider, fetchModels: suspend () -> List<String>,
    onAdd: (Collection<String>) -> Unit, onDismiss: () -> Unit,
) {
    val selection = remember { ModelImportSelection() }
    var attempt by remember { mutableIntStateOf(0) }
    val configured = remember(provider.models) { provider.models.mapTo(mutableSetOf()) { it.externalModelId.trim() } }
    val toAdd = selection.selected - configured
    val dismiss = { selection.discard(); onDismiss() }

    LaunchedEffect(attempt) { selection.load(fetchModels) }

    ModalBottomSheet(onDismissRequest = dismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.82f).imePadding().padding(horizontal = 16.dp)) {
            Text(stringResource(R.string.model_discovery_fetch), style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(10.dp))
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                when (val state = selection.result) {
                    ModelListResult.Loading -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator()
                        Spacer(Modifier.height(8.dp))
                        Text(stringResource(R.string.model_discovery_loading))
                    }
                    ModelListResult.Failed -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(stringResource(R.string.model_discovery_error))
                        TextButton(onClick = { attempt++ }) {
                            Text(stringResource(R.string.model_discovery_retry))
                        }
                    }
                    is ModelListResult.Loaded -> if (state.ids.isEmpty()) {
                        Text(stringResource(R.string.model_discovery_empty))
                    } else {
                        val matches = selection.matches
                        Column(Modifier.fillMaxSize()) {
                            OutlinedTextField(selection.query, { selection.query = it }, Modifier.fillMaxWidth(), singleLine = true,
                                label = { Text(stringResource(R.string.model_discovery_search)) },
                                leadingIcon = { Icon(Icons.Outlined.Search, null) })
                            LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
                                if (matches.isEmpty()) item {
                                    Text(stringResource(R.string.chat_picker_search_empty), Modifier.padding(vertical = 16.dp))
                                }
                                items(matches, key = { it }) { id ->
                                    val added = id in configured
                                    val checked = added || id in selection.selected
                                    Row(Modifier.fillMaxWidth().toggleable(value = checked, enabled = !added, role = Role.Checkbox,
                                        onValueChange = { selection.select(id, it, configured) }).heightIn(min = 48.dp),
                                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Checkbox(checked, onCheckedChange = null, enabled = !added)
                                        Text(id, Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                                        if (added) Text(stringResource(R.string.model_added), style = MaterialTheme.typography.labelSmall)
                                    }
                                }
                            }
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically) {
                if (toAdd.isNotEmpty()) Text(stringResource(R.string.drawer_selected_count, toAdd.size),
                    Modifier.weight(1f), style = MaterialTheme.typography.labelSmall)
                TextButton(onClick = dismiss) { Text(stringResource(R.string.settings_cancel)) }
                Button(enabled = toAdd.isNotEmpty(), onClick = { selection.confirm(configured, onAdd) }) {
                    Text(stringResource(R.string.model_discovery_add_selected))
                }
            }
        }
    }
}
