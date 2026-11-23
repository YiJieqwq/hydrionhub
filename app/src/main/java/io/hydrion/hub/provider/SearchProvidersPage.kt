package io.hydrion.hub.provider

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.LocalSaveableStateRegistry
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.hydrion.hub.R
import java.util.UUID

@Composable
internal fun SearchProvidersPage(store: WebSearchStore, onBack: () -> Unit) =
    SearchProvidersPage(store, onBack, manual = null)

@Composable
internal fun SearchProvidersPage(store: WebSearchStore, onBack: () -> Unit, manual: ManualSearchPort?) {
    val settings by store.settings.collectAsState()
    val tavily = manual?.tavily?.collectAsState()?.value
    var editingTavily by remember(tavily?.enabled) { mutableStateOf(false) }
    var editId by rememberSaveable { mutableStateOf<String?>(null) }
    val id = editId
    if (id != null) {
        key(id) { SearchEndpointEditor(settings.endpoints.find { it.id == id }, id, store, manual) { editId = null } }
        return
    }
    if (manual != null && tavily?.enabled == true && editingTavily) {
        TavilyEditor(manual, tavily) { editingTavily = false }
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
                        }) { StatusBadge(stringResource(if (endpoint.adapter == SearchAdapter.Searxng) R.string.search_searxng else R.string.search_not_connected), MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                }
                Text(stringResource(R.string.search_local_note), Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (manual != null && tavily != null) item {
                ModelSettingsCard {
                    Row(Modifier.fillMaxWidth().toggleable(tavily.enabled, role = Role.Switch,
                        onValueChange = manual::setPilotEnabled).heightIn(min = 48.dp).padding(horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.search_tavily_pilot), Modifier.weight(1f), fontSize = 15.sp)
                        Switch(tavily.enabled, onCheckedChange = null)
                    }
                    if (tavily.enabled) {
                        HorizontalDivider(thickness = .6.dp)
                        ModelSettingsRow(stringResource(if (tavily.added) R.string.search_tavily_name else R.string.search_tavily_add),
                            { editingTavily = true }, {
                                Icon(painterResource(R.drawable.lucide_earth), null, Modifier.size(22.dp))
                            }) {
                            if (tavily.added) StatusBadge(stringResource(
                                if (tavily.hasKey) R.string.search_tavily_key_ready else R.string.search_tavily_key_needed),
                                MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TavilyEditor(manual: ManualSearchPort, setup: TavilySetup, onBack: () -> Unit) {
    var searching by remember(setup.added) { mutableStateOf(false) }
    val name = stringResource(R.string.search_tavily_name)
    if (searching && setup.added) {
        ManualWebSearchPage(ManualTarget.Tavily, name, stringResource(R.string.search_tavily_notice),
            setup.hasKey, manual::search) { searching = false }
        return
    }
    var keyInput by remember { mutableStateOf("") }
    var invalid by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    BackHandler(onBack = onBack)
    ModelSettingsFrame(name, onBack, action = {
        if (setup.added) IconButton(onClick = { deleting = true }) {
            Icon(Icons.Outlined.Delete, stringResource(R.string.drawer_delete))
        }
        IconButton(onClick = {
            val submitted = keyInput.ifEmpty { null }
            keyInput = ""
            try {
                manual.saveTavily(true, submitted)
                invalid = false
            } catch (_: SearchFailure) {
                invalid = true
            }
        }, enabled = !setup.added || keyInput.isNotEmpty()) {
            Icon(Icons.Outlined.Check, stringResource(R.string.settings_save))
        }
    }) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            ModelSettingsCard {
                Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("https://api.tavily.com/search")
                    CompositionLocalProvider(LocalSaveableStateRegistry provides null) {
                        OutlinedTextField(keyInput, { keyInput = it; invalid = false }, Modifier.fillMaxWidth(),
                            label = { Text(stringResource(R.string.search_tavily_key)) },
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), singleLine = true)
                    }
                    Text(stringResource(R.string.model_session_key_note), style = MaterialTheme.typography.bodySmall)
                    if (invalid) Text(stringResource(R.string.search_tavily_invalid_key), color = MaterialTheme.colorScheme.error)
                    if (setup.hasKey) TextButton(onClick = {
                        manual.saveTavily(true, "")
                        keyInput = ""
                        invalid = false
                    }) { Text(stringResource(R.string.search_tavily_clear_key)) }
                    if (setup.added) {
                        if (!setup.hasKey) Text(stringResource(R.string.search_tavily_missing_key), style = MaterialTheme.typography.bodySmall)
                        OutlinedButton(onClick = { searching = true }, enabled = setup.hasKey && keyInput.isEmpty() && !invalid) {
                            Text(stringResource(R.string.search_manual))
                        }
                    }
                }
            }
        }
    }
    if (deleting) ConfirmRemoval(stringResource(R.string.search_remove, name), { deleting = false }) {
        manual.saveTavily(false)
        onBack()
    }
}

@Composable
private fun SearchEndpointEditor(original: SearchEndpoint?, id: String, store: WebSearchStore,
                                 manual: ManualSearchPort?, close: () -> Unit) {
    var name by rememberSaveable { mutableStateOf(original?.name.orEmpty()) }
    // Keep an unvalidated address out of the Android saved-instance bundle.
    var url by remember { mutableStateOf(original?.url.orEmpty()) }
    var searxng by rememberSaveable { mutableStateOf(original == null || original.adapter == SearchAdapter.Searxng) }
    val adapter = if (searxng) SearchAdapter.Searxng else SearchAdapter.None
    var testing by rememberSaveable { mutableStateOf(false) }
    val dirty = name != original?.name.orEmpty() || url != original?.url.orEmpty() ||
        searxng != (original == null || original.adapter == SearchAdapter.Searxng)
    if (testing && original?.adapter == SearchAdapter.Searxng && !dirty) {
        ManualWebSearchPage(original, manual) { testing = false }
        return
    }
    var invalid by remember { mutableStateOf(false) }
    var discard by rememberSaveable { mutableStateOf(false) }
    var delete by rememberSaveable { mutableStateOf(false) }
    val back = { if (dirty) discard = true else close() }
    BackHandler(onBack = back)
    ModelSettingsFrame(stringResource(if (original == null) R.string.model_settings_add_provider else R.string.search_edit_provider),
        back, action = {
            if (original != null) IconButton({ delete = true }) { Icon(Icons.Outlined.Delete, stringResource(R.string.drawer_delete)) }
            IconButton({
                if (store.save(SearchEndpoint(id, name, url, adapter))) close() else invalid = true
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
                        Row(Modifier.fillMaxWidth().toggleable(searxng, role = Role.Switch, onValueChange = { searxng = it })
                            .heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(R.string.search_use_searxng), Modifier.weight(1f))
                            Switch(searxng, onCheckedChange = null)
                        }
                        Text(stringResource(if (searxng) R.string.search_searxng_note else R.string.search_endpoint_note),
                            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (original?.adapter == SearchAdapter.Searxng && !dirty) {
                            OutlinedButton({ testing = true }) { Text(stringResource(R.string.search_manual)) }
                        }
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
