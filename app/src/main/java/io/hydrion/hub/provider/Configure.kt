package io.hydrion.hub.provider

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.hydrion.hub.R
import io.hydrion.hub.model.Mode
import io.hydrion.hub.model.RemoteModel
import io.hydrion.hub.ui.theme.HubShapes

@Composable
fun modelLabel(store: ProviderStore, mode: Mode): String {
    val catalog by store.catalog.collectAsState()
    return catalog.selection(mode).label
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConfigureSheet(store: ProviderStore, mode: Mode, onDismiss: () -> Unit) {
    val catalog by store.catalog.collectAsState()
    val models = remember(catalog, mode) { catalog.entries(mode) }
    var route by remember(mode, catalog) { mutableStateOf(catalog.selectedEntry(mode)?.route ?: models.firstOrNull()?.route) }
    val entry = models.firstOrNull { it.route == route }
    var edits by remember(mode, catalog) { mutableStateOf(emptyMap<RemoteModel, Map<String, String>>()) }
    val values = entry?.let { edits[it.route] ?: catalog.parameters(it.first, it.second) }.orEmpty()
    var choosing by rememberSaveable(mode) { mutableStateOf(false) }
    if (choosing) {
        ModelSelectionSheet(models, route, onDismiss = { choosing = false }) {
            route = it
            choosing = false
        }
        return
    }
    ModalBottomSheet(onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surface,
        shape = HubShapes.Sheet) {
        LazyColumn(Modifier.fillMaxWidth(), contentPadding = PaddingValues(start = 22.dp, end = 22.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                Text(stringResource(R.string.model_configure), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp))
            }
            if (entry == null) item { Text(stringResource(R.string.model_settings_no_models), color = MaterialTheme.colorScheme.onSurfaceVariant) }
            else {
                val model = entry.first
                item {
                    Surface(shape = HubShapes.Large, color = MaterialTheme.colorScheme.surfaceVariant) {
                        ModelSettingsRow(model.name, { choosing = true }, { BrowseAvatar(entry.second, model, 22.dp, browseColors()) })
                    }
                }
                items(model.configureCapabilities(), key = { it.key }) { cap ->
                    val current = values[cap.key].orEmpty()
                    ChoiceRow(cap.label, current, cap.choices.map { it to it }, current) { choice ->
                        edits = edits + (entry.route to model.choose(values, cap.key, choice))
                    }
                }
                item {
                    TextButton(onClick = {
                        catalog.defaultEntry(mode)?.let { default ->
                            route = default.route
                            edits = edits + (default.route to catalog.parameters(default.first, emptyMap()))
                        }
                    }) {
                        Text(stringResource(R.string.model_reset), color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
            item {
                Button(onClick = {
                    if (entry != null) store.configure(mode, entry.route, values)
                    onDismiss()
                }, modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.onSurface,
                        contentColor = MaterialTheme.colorScheme.surface)) { Text(stringResource(R.string.model_done)) }
            }
        }
    }
}

@Composable
internal fun <T> ChoiceRow(label: String, value: String, options: List<Pair<T, String>>, selected: T, onPick: (T) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        Surface(shape = HubShapes.Large, color = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.fillMaxWidth().clickable { expanded = true }) {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 15.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                Text(value, modifier = Modifier.weight(1f, fill = false), maxLines = 2, overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                Icon(Icons.Default.KeyboardArrowDown, null, modifier = Modifier.size(19.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { (id, title) ->
                DropdownMenuItem(text = { Text(title, color = if (id == selected) MaterialTheme.colorScheme.primary else Color.Unspecified) },
                    onClick = { onPick(id); expanded = false })
            }
        }
    }
}
