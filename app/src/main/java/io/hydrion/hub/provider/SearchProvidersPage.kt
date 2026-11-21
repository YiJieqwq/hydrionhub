package io.hydrion.hub.provider

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.hydrion.hub.R
import java.util.UUID

@Composable
internal fun SearchProvidersPage(store: WebSearchStore, onBack: () -> Unit) {
    val settings by store.settings.collectAsState()
    var editId by rememberSaveable { mutableStateOf<String?>(null) }
    val id = editId
    if (id != null) {
        key(id) { SearchEndpointEditor(settings.endpoints.find { it.id == id }, id, store) { editId = null } }
        return
    }
    ModelSettingsFrame(stringResource(R.string.settings_web_search), onBack, action = {
        IconButton({ editId = UUID.randomUUID().toString() }) {
            Icon(Icons.Outlined.Add, stringResource(R.string.model_settings_add_provider), Modifier.size(22.dp))
        }
    }) {
        LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 24.dp)) {
            item {
                Text(stringResource(R.string.chat_picker_provider), Modifier.padding(start = 12.dp, bottom = 6.dp), fontSize = 13.sp)
                ModelSettingsCard {
                    Row(Modifier.fillMaxWidth().toggleable(settings.localEnabled, role = Role.Switch, onValueChange = store::enableLocal)
                        .heightIn(min = 48.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.width(36.dp), contentAlignment = Alignment.Center) {
                            Icon(painterResource(R.drawable.lucide_earth), null, Modifier.size(22.dp))
                        }
                        Text(stringResource(R.string.search_local_demo), Modifier.weight(1f).padding(start = 12.dp), fontSize = 15.sp)
                        Switch(settings.localEnabled, onCheckedChange = null)
                    }
                    settings.endpoints.forEach { endpoint ->
                        HorizontalDivider(thickness = .6.dp)
                        ModelSettingsRow(endpoint.name, { editId = endpoint.id }, {
                            Icon(painterResource(R.drawable.lucide_earth), null, Modifier.size(22.dp))
                        }) { StatusBadge(stringResource(R.string.search_not_connected), MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                }
                Text(stringResource(R.string.search_local_note), Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun SearchEndpointEditor(original: SearchEndpoint?, id: String, store: WebSearchStore, close: () -> Unit) {
    var name by rememberSaveable { mutableStateOf(original?.name.orEmpty()) }
    // Keep an unvalidated address out of the Android saved-instance bundle.
    var url by remember { mutableStateOf(original?.url.orEmpty()) }
    var invalid by remember { mutableStateOf(false) }
    var discard by rememberSaveable { mutableStateOf(false) }
    var delete by rememberSaveable { mutableStateOf(false) }
    val back = { if (name != original?.name.orEmpty() || url != original?.url.orEmpty()) discard = true else close() }
    BackHandler(onBack = back)
    ModelSettingsFrame(stringResource(if (original == null) R.string.model_settings_add_provider else R.string.search_edit_provider),
        back, action = {
            if (original != null) IconButton({ delete = true }) { Icon(Icons.Outlined.Delete, stringResource(R.string.drawer_delete)) }
            IconButton({
                if (store.save(SearchEndpoint(id, name, url))) close() else invalid = true
            }) { Icon(Icons.Outlined.Check, stringResource(R.string.settings_save)) }
        }) {
        LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                ModelSettingsCard {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedTextField(name, { name = it; invalid = false }, Modifier.fillMaxWidth(), singleLine = true,
                            label = { Text(stringResource(R.string.search_provider_name)) })
                        OutlinedTextField(url, { url = it; invalid = false }, Modifier.fillMaxWidth(), singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                            label = { Text(stringResource(R.string.search_endpoint)) }, placeholder = { Text("https://") })
                        if (invalid) Text(stringResource(R.string.search_invalid), color = MaterialTheme.colorScheme.error)
                        Text(stringResource(R.string.search_endpoint_note), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
    if (discard) AlertDialog(onDismissRequest = { discard = false }, title = { Text(stringResource(R.string.model_settings_discard)) },
        confirmButton = { TextButton(close) { Text(stringResource(R.string.model_settings_drop)) } },
        dismissButton = { TextButton({ discard = false }) { Text(stringResource(R.string.model_settings_keep)) } })
    if (delete) ConfirmRemoval(stringResource(R.string.search_remove, original?.name.orEmpty()), { delete = false }) {
        store.remove(id)
        close()
    }
}
