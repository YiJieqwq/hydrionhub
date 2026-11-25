package io.hydrion.hub.ui.composer

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.hydrion.hub.R
import io.hydrion.hub.localtools.LocalMcpFixture
import io.hydrion.hub.localtools.mcpFile
import io.hydrion.hub.model.HubViewModel
import io.hydrion.hub.model.ManualPanelTool
import io.hydrion.hub.model.WorkspaceLoad
import io.hydrion.hub.ui.settings.assistantFeatureDirectory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal enum class HostToolPage(@StringRes val label: Int, val manualTool: ManualPanelTool? = null) {
    Environment(R.string.panel_environment), Settings(R.string.env_edit), Files(R.string.panel_files),
    Terminal(R.string.panel_terminal), Path(R.string.panel_path), Info(R.string.panel_info), Permissions(R.string.panel_policy),
    Time(R.string.panel_time, ManualPanelTool.Time), AskUser(R.string.panel_ask_user), Create(R.string.panel_create, ManualPanelTool.CreateEvent),
    Calendar(R.string.panel_calendar, ManualPanelTool.ViewCalendar), Browser(R.string.panel_browser, ManualPanelTool.Browser), Mcp(R.string.panel_mcp_manage)
}

@Composable
internal fun <T> panelData(source: Any, revision: Int = 0, read: suspend () -> T): Result<T>? = key(source, revision) {
    produceState<Result<T>?>(null) {
        value = try { Result.success(read()) }
        catch (cancel: CancellationException) { throw cancel }
        catch (failure: Exception) { Result.failure(failure) }
    }.value
}

private data class Entry(val page: HostToolPage, val icon: ImageVector, val detail: String? = null,
    val enabled: Boolean = true)

@Composable
internal fun HostToolGroups(vm: HubViewModel, query: String, legacyMatches: Boolean) {
    val state by vm.state.collectAsStateWithLifecycle()
    val ready by vm.workspaceLoad.collectAsStateWithLifecycle()
    val chat = state.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var revision by remember { mutableIntStateOf(0) }
    // A system handler may have been installed or disabled while the host was in the background.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { revision++ }
    val environmentRevision by vm.environments.revision.collectAsStateWithLifecycle()
    val catalog = panelData(vm.environments to environmentRevision, revision) { vm.environments.catalog() }
    val file = remember(context, chat.assistantId) { mcpFile(context.assistantFeatureDirectory(), chat.assistantId) }
    val mcp = panelData(file, revision) { file.read() }
    val env = catalog?.getOrNull()?.environments?.find { it.id == chat.environmentId }
    val selectedName = env?.name?.ifBlank { env.id.take(8) } ?: stringResource(when {
        catalog == null -> R.string.panel_loading
        catalog.isFailure -> R.string.runtime_error
        chat.environmentId != null -> R.string.header_environment_unavailable
        else -> R.string.panel_unbound
    })
    var destination by remember(chat.id, chat.assistantId, chat.environmentId) { mutableStateOf<HostToolPage?>(null) }
    var saving by remember(file) { mutableStateOf(false) }
    var saveError by remember(file) { mutableStateOf(false) }
    val canUse = ready == WorkspaceLoad.Ready
    val denied = chat.panelActionsDenied
    val gate = when {
        !canUse -> stringResource(R.string.feature_unavailable)
        denied -> stringResource(R.string.panel_denied)
        else -> null
    }
    val needsEnv = if (env == null) stringResource(R.string.panel_select_first) else null
    val handlers = panelData(context.packageManager, revision) {
        withContext(Dispatchers.IO) {
            PanelAppAction.entries.associateWith { action ->
                action.intent(address = "https://example.invalid/")?.resolveActivity(context.packageManager) != null
            }
        }
    }
    fun show(page: HostToolPage) { destination = page }
    @Composable fun appRow(page: HostToolPage, icon: ImageVector, action: PanelAppAction, detail: Int): Entry {
        val available = handlers?.getOrNull()?.get(action) == true
        val description = when {
            handlers == null -> R.string.panel_loading
            handlers.isFailure -> R.string.feature_unavailable
            !available -> R.string.panel_missing_app
            else -> detail
        }
        return Entry(page, icon, gate ?: stringResource(description), gate == null && available)
    }
    val envRows = listOf(
        Entry(HostToolPage.Environment, Icons.Outlined.Folder, selectedName, canUse && catalog != null),
        Entry(HostToolPage.Settings, Icons.Outlined.Settings),
        Entry(HostToolPage.Files, Icons.Outlined.FolderOpen, needsEnv ?: gate, env != null && gate == null),
        Entry(HostToolPage.Terminal, Icons.Outlined.Terminal, needsEnv ?: stringResource(R.string.panel_terminal_detail), env != null),
        Entry(HostToolPage.Path, Icons.Outlined.Folder, needsEnv ?: gate ?: env?.guestCwd, env != null && gate == null),
        Entry(HostToolPage.Info, Icons.Outlined.Info, needsEnv ?: catalog?.getOrNull()?.containers?.find { it.id == env?.containerId }?.name, env != null),
        Entry(HostToolPage.Permissions, Icons.Outlined.Security, stringResource(if (denied) R.string.panel_deny else R.string.panel_ask), canUse)
    )
    val localRows = listOf(
        Entry(HostToolPage.Time, Icons.Outlined.AccessTime),
        Entry(HostToolPage.AskUser, Icons.Outlined.QuestionAnswer, stringResource(R.string.panel_ask_unavailable), false),
        appRow(HostToolPage.Create, Icons.Outlined.Event, PanelAppAction.CreateEvent, R.string.panel_calendar_create_detail),
        appRow(HostToolPage.Calendar, Icons.Outlined.DateRange, PanelAppAction.ViewEvents, R.string.panel_calendar_view_detail),
        appRow(HostToolPage.Browser, Icons.Outlined.Language, PanelAppAction.Browser, R.string.panel_browser_detail)
    )
    @Composable fun matches(title: String, entries: List<Entry>): List<Entry> {
        val labels = entries.associate { it.page to stringResource(it.page.label) }
        return matchingToolChoices(query, title, entries) { labels.getValue(it.page) + " " + it.detail.orEmpty() }
    }
    @Composable fun row(entry: Entry, modifier: Modifier = Modifier) {
        PanelActionRow(stringResource(entry.page.label), entry.icon, entry.detail, entry.enabled, entry.page.name, modifier) { show(entry.page) }
    }
    val workTitle = stringResource(R.string.panel_work)
    val work = matches(workTitle, envRows)
    PanelSection("environment", workTitle, selectedName, work.isNotEmpty(), true, query.isNotEmpty()) {
        Column {
            // On an unfiltered panel the name opens binding/settings, with the two host actions alongside.
            // A search for an individual action also exposes its full label and availability reason.
            val inline = query.isEmpty()
            if (inline) {
                val binding = envRows.first()
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    row(binding, Modifier.weight(1f))
                    for (entry in envRows.filter { it.page == HostToolPage.Files || it.page == HostToolPage.Terminal }) {
                        IconButton(onClick = { show(entry.page) }, enabled = entry.enabled,
                            modifier = Modifier.testTag("panel-action:" + entry.page.name).keepToolEditorFocus()) {
                            Icon(entry.icon, stringResource(entry.page.label), Modifier.size(20.dp))
                        }
                    }
                }
            }
            for (entry in work) {
                if (!inline || entry.page !in setOf(HostToolPage.Environment, HostToolPage.Settings, HostToolPage.Files, HostToolPage.Terminal)) row(entry)
            }
        }
    }
    val localTitle = stringResource(R.string.panel_local)
    val local = matches(localTitle, localRows)
    val choices = state.assistants.find { it.id == chat.assistantId }?.manualPanelTools.orEmpty()
    val active = localRows.count { canUse && it.enabled && it.page.manualTool in choices }
    PanelSection("local", localTitle, stringResource(R.string.panel_manual_only, active, localRows.size), local.isNotEmpty(), false, query.isNotEmpty()) {
        Column {
            for (entry in local) {
                val tool = entry.page.manualTool
                val available = entry.enabled && canUse && tool != null
                val selected = available && tool in choices
                val title = stringResource(entry.page.label)
                val switchLabel = stringResource(R.string.panel_switch, title)
                Row(Modifier.fillMaxWidth().padding(end = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    PanelActionRow(title, entry.icon, entry.detail, selected, entry.page.name, Modifier.weight(1f),
                        highlight = selected, chevron = false) { show(entry.page) }
                    Switch(selected, onCheckedChange = { on -> if (tool != null) vm.setManualPanelTool(chat.assistantId, tool, on) },
                        enabled = available, modifier = Modifier.testTag("panel-switch:" + entry.page.name)
                            .keepToolEditorFocus().semantics { contentDescription = switchLabel })
                }
            }
        }
    }
    val mcpTitle = stringResource(R.string.panel_mcp)
    val manageTitle = stringResource(R.string.panel_mcp_manage)
    val servers = mcp?.getOrNull()?.servers.orEmpty()
    val serverMatches = matchingToolChoices(query, mcpTitle, servers) { it.definition.name }
    val showManage = matchingToolChoices(query, mcpTitle, listOf(manageTitle)) { it }.isNotEmpty()
    val mcpVisible = serverMatches.isNotEmpty() || showManage
    val serverCount = if (mcp?.isSuccess == true) "${servers.count { it.canTest }}/${servers.size}" else "—"
    PanelSection("mcp", mcpTitle, serverCount, mcpVisible, true, query.isNotEmpty()) {
        Column {
            if (showManage && servers.isEmpty()) {
                val status = when {
                    mcp == null -> R.string.panel_loading
                    mcp.isFailure -> R.string.feature_unavailable
                    else -> R.string.panel_no_servers
                }
                Text(stringResource(status), Modifier.padding(horizontal = 16.dp, vertical = 6.dp), style = MaterialTheme.typography.bodySmall)
            }
            for (server in serverMatches) {
                val local = server.definition.endpoint == null
                val selectable = local && server.trusted && canUse
                val detail = when {
                    !local -> R.string.mcp_config_only
                    !server.trusted -> R.string.panel_untrusted
                    else -> R.string.mcp_in_process
                }
                val description = if (local) {
                    val count = LocalMcpFixture.toolNames.size
                    stringResource(detail) + " · " + pluralStringResource(R.plurals.panel_tool_count, count, count)
                } else stringResource(detail)
                PanelActionRow(server.definition.name, Icons.Outlined.Dns, description, canUse && !saving,
                    server.id, checked = server.enabled.takeIf { selectable }) {
                    if (!selectable) show(HostToolPage.Mcp)
                    else if (!saving && vm.state.value.current.assistantId == chat.assistantId) {
                        saving = true
                        scope.launch {
                            try {
                                file.update { latest ->
                                    val live = latest.servers.single { it.id == server.id }
                                    require(live.trusted && live.definition == server.definition)
                                    latest.enable(live.id, !server.enabled)
                                }
                                saveError = false
                            } catch (cancel: CancellationException) { throw cancel }
                            catch (_: Exception) { saveError = true }
                            finally { saving = false; revision++ }
                        }
                    }
                }
            }
            if (saveError) Text(stringResource(R.string.runtime_error), color = MaterialTheme.colorScheme.error)
            if (showManage) PanelActionRow(manageTitle, Icons.Outlined.Settings,
                state.assistants.find { it.id == chat.assistantId }?.name, canUse, "Mcp") { show(HostToolPage.Mcp) }
        }
    }
    if (!legacyMatches && work.isEmpty() && local.isEmpty() && !mcpVisible) {
        Text(stringResource(R.string.chat_tools_empty), Modifier.fillMaxWidth().testTag("chat-tools-empty")
            .semantics { liveRegion = LiveRegionMode.Polite }.padding(8.dp), style = MaterialTheme.typography.bodyMedium)
    }
    destination?.let { page ->
        key(chat.id, chat.environmentId, page) {
            HostToolDestination(page, vm, chat, catalog?.getOrNull(), env, onSettings = { show(HostToolPage.Settings) }) {
                destination = null
                if (page in listOf(HostToolPage.Mcp, HostToolPage.Settings, HostToolPage.Path, HostToolPage.Environment)) revision++
            }
        }
    }
}

@Composable
private fun Modifier.keepToolEditorFocus(): Modifier {
    val input = LocalInputModeManager.current
    return focusProperties { canFocus = input.inputMode != InputMode.Touch }
}

@Composable
internal fun PanelActionRow(title: String, icon: ImageVector, detail: String?, enabled: Boolean, id: String,
    modifier: Modifier = Modifier, checked: Boolean? = null, highlight: Boolean = checked == true, chevron: Boolean = true, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val labelColor = when {
        highlight -> colors.primary
        enabled -> colors.onSurface
        else -> colors.onSurfaceVariant
    }
    val action = if (checked == null) Modifier.clickable(enabled = enabled, role = Role.Button, onClick = onClick)
        else Modifier.toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = { onClick() })
    Row(modifier.fillMaxWidth().clip(MaterialTheme.shapes.small)
        .testTag("panel-action:" + id).keepToolEditorFocus().then(action)
        .heightIn(min = 48.dp).padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Icon(icon, null, Modifier.size(20.dp), tint = if (highlight) colors.primary else colors.onSurfaceVariant)
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, color = labelColor)
            if (!detail.isNullOrEmpty()) Text(detail, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
        }
        if (checked != null) Switch(checked = checked, onCheckedChange = null, enabled = enabled)
        else if (enabled && chevron) Icon(Icons.Outlined.ChevronRight, null, Modifier.size(16.dp), tint = colors.onSurfaceVariant)
    }
}
