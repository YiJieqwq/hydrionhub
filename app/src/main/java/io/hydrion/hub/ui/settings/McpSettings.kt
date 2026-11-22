package io.hydrion.hub.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.hydrion.hub.R
import io.hydrion.hub.localtools.*
import io.hydrion.hub.model.HubViewModel
import io.hydrion.hub.provider.ModelSettingsCard

private enum class McpEditor { Add, Edit, Import }

@Composable
internal fun McpSettings(vm: HubViewModel, onBack: () -> Unit, ownerId: String? = null) {
    ScopedToolPage(vm, R.string.settings_mcp, onBack, ownerId) { root, owner ->
        val file = remember(root, owner) { mcpFile(root, owner) }
        FeatureContent(file) { catalog, saving, update ->
            var editor by remember { mutableStateOf<McpEditor?>(null) }
            var edited by remember { mutableStateOf<McpServer?>(null) }
            val localName = stringResource(R.string.mcp_fixture)
            LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                item {
                    Column {
                        TextButton(onClick = { edited = null; editor = McpEditor.Add }, enabled = !saving && catalog.servers.size < 64) {
                            Text(stringResource(R.string.mcp_add))
                        }
                        TextButton(onClick = { edited = null; editor = McpEditor.Import }, enabled = !saving && catalog.servers.size < 64) {
                            Text(stringResource(R.string.mcp_import))
                        }
                        if (catalog.servers.none { it.definition.endpoint == null })
                            TextButton(onClick = { update { it.add(listOf(McpDefinition(localName, null))) } },
                                enabled = !saving && catalog.servers.size < 64) { Text(stringResource(R.string.mcp_add_fixture)) }
                    }
                }
                items(catalog.servers, key = { it.id }) { server ->
                    McpServerRow(owner, server, saving, update, onEdit = { edited = server; editor = McpEditor.Edit })
                }
            }
            editor?.let { mode ->
                McpEntryDialog(mode, edited, 64 - catalog.servers.size, dismiss = { editor = null }) { entries ->
                    val prior = edited
                    update { latest -> if (prior != null) latest.replace(prior.id, entries.single()) else latest.add(entries) }
                    editor = null
                }
            }
        }
    }
}

@Composable
private fun McpServerRow(owner: String, server: McpServer, saving: Boolean,
    update: ((McpCatalog) -> McpCatalog) -> Unit, onEdit: () -> Unit) {
    var trustPrompt by remember(server) { mutableStateOf(false) }
    var deletePrompt by remember(server) { mutableStateOf(false) }
    var review by remember(owner, server) { mutableStateOf<McpFixtureReview?>(null) }
    var reply by remember(owner, server) { mutableStateOf<String?>(null) }
    ModelSettingsCard {
        Column(Modifier.padding(12.dp)) {
            Text(server.definition.name, style = MaterialTheme.typography.titleMedium)
            Text(server.definition.endpoint ?: stringResource(R.string.mcp_in_process))
            if (server.definition.endpoint != null) Text(stringResource(R.string.mcp_config_only), style = MaterialTheme.typography.bodySmall)
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(enabled = !saving, onClick = {
                    if (server.trusted) update { it.trust(server.id, false) } else trustPrompt = true
                }) { Text(stringResource(if (server.trusted) R.string.mcp_revoke else R.string.mcp_trust)) }
                Spacer(Modifier.weight(1f))
                Text(stringResource(R.string.mcp_enabled))
                Switch(checked = server.enabled, enabled = server.trusted && !saving,
                    onCheckedChange = { value -> update { it.enable(server.id, value) } })
            }
            Row {
                TextButton(onClick = onEdit, enabled = !saving) { Text(stringResource(R.string.mcp_edit)) }
                TextButton(onClick = { deletePrompt = true }, enabled = !saving) { Text(stringResource(R.string.mcp_delete)) }
            }
            if (server.canTest) {
                TextButton(onClick = { reply = null; review = McpFixtureReview.discover(owner, server) }, enabled = !saving) {
                    Text(stringResource(R.string.mcp_discover))
                }
                reply?.let { Text(stringResource(R.string.mcp_response, it)) }
            }
        }
    }
    if (trustPrompt) McpPrompt(R.string.mcp_trust, server.definition.name, { trustPrompt = false }) {
        trustPrompt = false
        update { it.trust(server.id, true) }
    }
    if (deletePrompt) McpPrompt(R.string.mcp_delete, server.definition.name, { deletePrompt = false }) {
        deletePrompt = false
        update { it.remove(server.id) }
    }
    review?.let { probe ->
        McpPrompt(R.string.mcp_run, stringResource(R.string.mcp_call_confirm, probe.tool), { review = null }) {
            review = null
            reply = probe.confirm(owner, server)
        }
    }
}

@Composable
private fun McpPrompt(title: Int, detail: String, dismiss: () -> Unit, confirm: () -> Unit) {
    AlertDialog(onDismissRequest = dismiss, title = { Text(stringResource(title)) }, text = { Text(detail) },
        confirmButton = { TextButton(onClick = confirm) { Text(stringResource(title)) } },
        dismissButton = { TextButton(onClick = dismiss) { Text(stringResource(android.R.string.cancel)) } })
}

@Composable
private fun McpEntryDialog(mode: McpEditor, original: McpServer?, slots: Int, dismiss: () -> Unit, accept: (List<McpDefinition>) -> Unit) {
    var name by remember { mutableStateOf(original?.definition?.name.orEmpty()) }
    var endpoint by remember { mutableStateOf(original?.definition?.endpoint.orEmpty()) }
    var json by remember { mutableStateOf("") }
    var preview by remember { mutableStateOf<List<McpDefinition>?>(null) }
    var invalid by remember { mutableStateOf(false) }
    val title = when (mode) { McpEditor.Add -> R.string.mcp_add; McpEditor.Edit -> R.string.mcp_edit; McpEditor.Import -> R.string.mcp_import }
    AlertDialog(onDismissRequest = dismiss, title = { Text(stringResource(title)) }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val rows = preview
            if (rows != null) {
                Text(stringResource(R.string.mcp_review))
                rows.forEach { Text(it.name); it.endpoint?.let { url -> Text(url, style = MaterialTheme.typography.bodySmall) } }
            } else if (mode == McpEditor.Import) {
                Text(stringResource(R.string.mcp_import_hint), style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(json, { json = it.take(16_385); invalid = false }, label = { Text("JSON") }, maxLines = 6)
            } else {
                OutlinedTextField(name, { name = it.take(81); invalid = false }, singleLine = true, label = { Text(stringResource(R.string.mcp_name)) })
                if (original == null || original.definition.endpoint != null) {
                    OutlinedTextField(endpoint, { endpoint = it.take(513); invalid = false }, singleLine = true, label = { Text("HTTPS URL") })
                    Text(stringResource(R.string.mcp_endpoint_hint), style = MaterialTheme.typography.bodySmall)
                }
            }
            if (invalid) Text(stringResource(R.string.mcp_invalid), color = MaterialTheme.colorScheme.error)
        }
    }, confirmButton = {
        TextButton(onClick = {
            val validated = preview
            if (validated != null) accept(validated) else {
                val rows = runCatching {
                    if (mode == McpEditor.Import) reviewMcpImport(json)
                    else listOf(McpDefinition(name.trim(), if (original != null && original.definition.endpoint == null) null else endpoint.trim()))
                }.getOrNull()
                if (rows == null || (original == null && rows.size > slots)) invalid = true
                else if (mode == McpEditor.Import) { json = ""; preview = rows } else accept(rows)
            }
        }) { Text(stringResource(if (mode == McpEditor.Import && preview == null) R.string.mcp_preview else R.string.mcp_save)) }
    }, dismissButton = { TextButton(onClick = dismiss) { Text(stringResource(android.R.string.cancel)) } })
}
