package io.hydrion.hub.ui.composer

import android.content.ActivityNotFoundException
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.hydrion.hub.R
import io.hydrion.hub.model.Conversation
import io.hydrion.hub.model.HubViewModel
import io.hydrion.hub.model.WorkspaceLoad
import io.hydrion.hub.provider.ModelSettingsFrame
import io.hydrion.hub.runtime.ProotCatalog
import io.hydrion.hub.runtime.ProotEnvironment
import io.hydrion.hub.runtime.ProotPreparation
import io.hydrion.hub.ui.settings.ContainerSettings
import io.hydrion.hub.ui.settings.McpSettings
import io.hydrion.hub.ui.settings.runtimeUnavailableLabel
import java.text.DateFormat
import java.util.Date
import java.util.TimeZone
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun HostToolDestination(page: HostToolPage, vm: HubViewModel, chat: Conversation, catalog: ProotCatalog?,
    env: ProotEnvironment?, onSettings: () -> Unit, close: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val title = stringResource(page.label)
    val gateOpen = !chat.panelActionsDenied
    fun current() = vm.workspaceLoad.value == WorkspaceLoad.Ready && vm.state.value.current.let {
        it.id == chat.id && it.assistantId == chat.assistantId && it.environmentId == chat.environmentId
    }
    fun permitted() = current() && !vm.state.value.current.panelActionsDenied && (page.manualTool?.let { tool ->
        vm.state.value.assistants.any { it.id == chat.assistantId && tool in it.manualPanelTools }
    } ?: true)
    when (page) {
        HostToolPage.Settings -> PanelWindow(close) { ContainerSettings(vm, close) }
        HostToolPage.Mcp -> PanelWindow(close) {
            ModelSettingsFrame(title, close) { McpSettings(vm, close, ownerId = chat.assistantId) }
        }
        HostToolPage.Environment -> {
            var failure by remember { mutableStateOf(false) }
            var busy by remember { mutableStateOf(false) }
            fun bind(id: String?) {
                if (busy || !current()) return
                busy = true
                scope.launch {
                    try { vm.bindEnvironment(chat.id, id); close() }
                    catch (cancel: CancellationException) { throw cancel }
                    catch (_: Exception) { failure = true }
                    finally { busy = false }
                }
            }
            PanelDialog(stringResource(R.string.panel_choose), close) {
                Column(Modifier.verticalScroll(rememberScrollState()).selectableGroup()) {
                    if (catalog == null) Text(stringResource(R.string.runtime_error))
                    else if (catalog.environments.isEmpty()) Text(stringResource(R.string.env_none))
                    for (item in catalog?.environments.orEmpty()) {
                        val name = item.name.ifBlank { item.id.take(8) }
                        RadioChoice(name, chat.environmentId == item.id, !busy) { bind(item.id) }
                    }
                    if (chat.environmentId != null) TextButton({ bind(null) }, enabled = !busy) {
                        Text(stringResource(R.string.panel_clear_binding))
                    }
                    TextButton(onSettings, enabled = !busy) { Text(stringResource(R.string.env_edit)) }
                    if (failure) Text(stringResource(R.string.runtime_error), color = MaterialTheme.colorScheme.error)
                }
            }
        }
        HostToolPage.Permissions -> PanelDialog(title, close) {
            Column(Modifier.verticalScroll(rememberScrollState()).selectableGroup()) {
                Text(stringResource(R.string.panel_permission_note), style = MaterialTheme.typography.bodyMedium)
                for (deny in listOf(false, true)) {
                    RadioChoice(stringResource(if (deny) R.string.panel_deny else R.string.panel_ask), chat.panelActionsDenied == deny,
                        detail = stringResource(if (deny) R.string.panel_permission_deny_detail else R.string.panel_permission_ask_detail)) {
                        if (current()) vm.setPanelActionsDenied(chat.id, deny)
                        close()
                    }
                }
            }
        }
        HostToolPage.Terminal, HostToolPage.Info -> if (env != null) {
            val ready = panelData(env) { vm.environments.prepare(env.id) }
            PanelDialog(title, close) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(env.name.ifBlank { env.id.take(8) }, style = MaterialTheme.typography.titleMedium)
                    val container = catalog?.containers?.find { it.id == env.containerId }
                    Text(stringResource(R.string.container_section) + " · " + container?.let { it.name.ifBlank { it.id.take(8) } }.orEmpty())
                    Text(env.guestCwd)
                    val status = when (val result = ready?.getOrNull()) {
                        is ProotPreparation.Unavailable -> runtimeUnavailableLabel(result.reason)
                        is ProotPreparation.Ready -> R.string.panel_layout_ready
                        null -> if (ready == null) R.string.panel_loading else R.string.runtime_error
                    }
                    Text(stringResource(status))
                    if (page == HostToolPage.Terminal) Text(stringResource(R.string.panel_terminal_unavailable))
                    TextButton(onSettings) { Text(stringResource(R.string.env_edit)) }
                }
            }
        }
        HostToolPage.Files, HostToolPage.Path -> if (env != null && gateOpen) {
            // Approval is deliberately local to this open page and this binding.
            var approved by remember(chat.id, env.id, gateOpen) { mutableStateOf(false) }
            if (!approved) PanelApproval(title, stringResource(R.string.panel_confirm_files), close) {
                if (permitted()) approved = true else close()
            } else WorkspaceDirectoryPage(vm, env, selecting = page == HostToolPage.Path, permitted = ::permitted, close = close)
        }
        HostToolPage.Time -> {
            val locale = LocalConfiguration.current.locales[0]
            val value = remember(locale) {
                DateFormat.getDateTimeInstance(DateFormat.FULL, DateFormat.LONG, locale).format(Date()) + "\n" + TimeZone.getDefault().id
            }
            PanelDialog(title, close) { Text(value, Modifier.testTag("panel-current-time")) }
        }
        HostToolPage.Create, HostToolPage.Calendar, HostToolPage.Browser -> if (gateOpen) {
            val action = when (page) {
                HostToolPage.Create -> PanelAppAction.CreateEvent
                HostToolPage.Calendar -> PanelAppAction.ViewEvents
                else -> PanelAppAction.Browser
            }
            var address by remember { mutableStateOf("") }
            var failed by remember(address) { mutableStateOf(false) }
            val reason = when (action) {
                PanelAppAction.CreateEvent -> R.string.panel_calendar_create_detail
                PanelAppAction.ViewEvents -> R.string.panel_calendar_view_detail
                PanelAppAction.Browser -> R.string.panel_browser_detail
            }
            fun openApp() {
                if (!permitted()) { close(); return }
                val intent = action.intent(address = address) ?: return
                try {
                    if (intent.resolveActivity(context.packageManager) == null) failed = true
                    else { context.startActivity(intent); close() }
                } catch (_: ActivityNotFoundException) { failed = true }
                catch (_: SecurityException) { failed = true }
            }
            if (action == PanelAppAction.Browser) {
                PanelWindow(close) {
                    ModelSettingsFrame(title, close) {
                        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text(stringResource(reason))
                            OutlinedTextField(address, { address = it }, Modifier.fillMaxWidth(), singleLine = true,
                                label = { Text(stringResource(R.string.panel_address)) })
                            if (failed) Text(stringResource(R.string.panel_app_failed), color = MaterialTheme.colorScheme.error)
                            Button(onClick = ::openApp, enabled = action.intent(address = address) != null) {
                                Text(stringResource(R.string.panel_open_app))
                            }
                        }
                    }
                }
            } else AlertDialog(onDismissRequest = close, title = { Text(title) }, text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(reason))
                    if (failed) Text(stringResource(R.string.panel_app_failed), color = MaterialTheme.colorScheme.error)
                }
            }, confirmButton = { TextButton(::openApp) { Text(stringResource(R.string.panel_open_app)) } },
                dismissButton = { TextButton(close) { Text(stringResource(android.R.string.cancel)) } })
        }
        HostToolPage.AskUser -> Unit
    }
    if (!gateOpen && page in listOf(HostToolPage.Files, HostToolPage.Path, HostToolPage.Create, HostToolPage.Calendar, HostToolPage.Browser))
        LaunchedEffect(page) { close() }
}

@Composable
private fun RadioChoice(title: String, selected: Boolean, enabled: Boolean = true, detail: String? = null, select: () -> Unit) {
    Row(Modifier.fillMaxWidth().selectable(selected, enabled = enabled, role = Role.RadioButton, onClick = select)
        .heightIn(min = 48.dp).padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (detail != null) Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (selected) Icon(Icons.Outlined.Check, null, tint = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun PanelWindow(close: () -> Unit, content: @Composable () -> Unit) {
    Dialog(onDismissRequest = close, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Surface(Modifier.fillMaxSize(), content = content)
    }
}

@Composable
private fun PanelDialog(title: String, close: () -> Unit, content: @Composable () -> Unit) {
    AlertDialog(onDismissRequest = close, title = { Text(title) }, text = content,
        confirmButton = { TextButton(close) { Text(stringResource(android.R.string.ok)) } })
}

@Composable
private fun PanelApproval(title: String, detail: String, close: () -> Unit, allow: () -> Unit) {
    AlertDialog(onDismissRequest = close, title = { Text(title) }, text = { Text(detail) },
        confirmButton = { TextButton(allow) { Text(stringResource(R.string.panel_allow_once)) } },
        dismissButton = { TextButton(close) { Text(stringResource(android.R.string.cancel)) } })
}

@Composable
private fun DirectoryRow(name: String, icon: ImageVector, enabled: Boolean, id: String, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small).testTag("panel-action:$id").keepToolEditorFocus()
        .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
        .heightIn(min = 48.dp).padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Icon(icon, null, Modifier.size(20.dp), tint = colors.onSurfaceVariant)
        Text(name, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium,
            color = if (enabled) colors.onSurface else colors.onSurfaceVariant)
        if (enabled) Icon(Icons.Outlined.ChevronRight, null, Modifier.size(16.dp), tint = colors.onSurfaceVariant)
    }
}

@Composable
private fun WorkspaceDirectoryPage(vm: HubViewModel, env: ProotEnvironment, selecting: Boolean, permitted: () -> Boolean, close: () -> Unit) {
    var path by rememberSaveable(env.id) { mutableStateOf(env.guestCwd) }
    var busy by remember { mutableStateOf(false) }
    var error by remember(path) { mutableStateOf(false) }
    var revision by remember { mutableIntStateOf(0) }
    val entries = panelData(env.id to path, revision) { vm.environments.directory(env.id, path) }
    val scope = rememberCoroutineScope()
    fun parent() { path = path.substringBeforeLast('/') }
    PanelWindow(close) {
        ModelSettingsFrame(stringResource(if (selecting) R.string.panel_path else R.string.panel_files), close) {
            Column(Modifier.padding(horizontal = 16.dp)) {
                Text(path, Modifier.testTag("panel-directory-path"), style = MaterialTheme.typography.titleSmall)
                if (selecting) Text(stringResource(R.string.panel_path_shared), style = MaterialTheme.typography.bodySmall)
                if (path != "/workspace") DirectoryRow(stringResource(R.string.panel_parent_folder), Icons.Outlined.ArrowUpward,
                    !busy, "Parent") { parent() }
                if (error || entries?.isFailure == true) {
                    Text(stringResource(if (error) R.string.runtime_error else R.string.panel_directory_failed), color = MaterialTheme.colorScheme.error)
                    TextButton({ revision++; error = false }) { Text(stringResource(R.string.env_retry)) }
                }
                if (entries == null) LinearProgressIndicator(Modifier.fillMaxWidth())
                val items = entries?.getOrNull().orEmpty()
                if (entries?.isSuccess == true && items.isEmpty()) Text(stringResource(R.string.panel_empty_folder), Modifier.padding(vertical = 12.dp))
                LazyColumn(Modifier.weight(1f)) {
                    items(items, key = { it.name }) { item ->
                        if (item.directory) DirectoryRow(item.name, Icons.Outlined.Folder, !busy, "folder:" + item.name) {
                            if (permitted()) path += "/" + item.name
                        } else Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Icon(Icons.Outlined.Description, null, Modifier.size(20.dp))
                            Text(item.name, Modifier.weight(1f))
                            Text(android.text.format.Formatter.formatShortFileSize(LocalContext.current, item.bytes), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                if (selecting) Button(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    enabled = !busy && entries?.isSuccess == true, onClick = {
                        if (!permitted() || busy) return@Button
                        busy = true
                        scope.launch {
                            try { vm.environments.setWorkingDirectory(env.id, env.guestCwd, path); close() }
                            catch (cancel: CancellationException) { throw cancel }
                            catch (_: Exception) { error = true }
                            finally { busy = false }
                        }
                    }) { Text(stringResource(R.string.panel_use_folder)) }
            }
        }
    }
}
