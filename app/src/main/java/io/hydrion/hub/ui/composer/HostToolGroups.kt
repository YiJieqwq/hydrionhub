package io.hydrion.hub.ui.composer

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
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
import io.hydrion.hub.model.Mode
import io.hydrion.hub.model.canAskUser
import io.hydrion.hub.ui.settings.assistantFeatureDirectory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal enum class HostToolPage(@StringRes val label: Int, val manualTool: ManualPanelTool? = null) {
    Environment(R.string.panel_environment), Settings(R.string.env_edit), Files(R.string.panel_files),
    Terminal(R.string.panel_terminal), Path(R.string.panel_path), Info(R.string.panel_info), Permissions(R.string.panel_policy),
    Time(R.string.panel_time, ManualPanelTool.Time), AskUser(R.string.panel_ask_user, ManualPanelTool.AskUser), Create(R.string.panel_create, ManualPanelTool.CreateEvent),
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

private data class Entry(val page: HostToolPage, @DrawableRes val icon: Int, val detail: String? = null,
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
    @Composable fun appRow(page: HostToolPage, @DrawableRes icon: Int, action: PanelAppAction, detail: Int): Entry {
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
        Entry(HostToolPage.Environment, R.drawable.lucide_folder_code, selectedName, canUse && catalog != null),
        Entry(HostToolPage.Settings, R.drawable.lucide_settings2),
        Entry(HostToolPage.Files, R.drawable.lucide_folder_open, needsEnv ?: gate, env != null && gate == null),
        Entry(HostToolPage.Terminal, R.drawable.lucide_terminal, needsEnv ?: stringResource(R.string.panel_terminal_detail), env != null),
        Entry(HostToolPage.Path, R.drawable.lucide_folder, needsEnv ?: gate ?: env?.guestCwd, env != null && gate == null),
        Entry(HostToolPage.Info, R.drawable.lucide_hard_drive, needsEnv ?: catalog?.getOrNull()?.containers?.find { it.id == env?.containerId }?.name, env != null),
        Entry(HostToolPage.Permissions, R.drawable.lucide_shield, stringResource(if (denied) R.string.panel_deny else R.string.panel_ask), canUse)
    )
    val models by vm.providers.catalog.collectAsStateWithLifecycle()
    val questionModel = models.selectedEntry(Mode.Chat)?.let { (model, provider) -> model.canAskUser(provider) } == true
    val localRows = listOf(
        Entry(HostToolPage.Time, R.drawable.lucide_clock),
        Entry(HostToolPage.AskUser, R.drawable.lucide_message_circle_question,
            gate ?: stringResource(if (questionModel) R.string.ask_panel_help else R.string.panel_ask_unavailable), questionModel && gate == null),
        appRow(HostToolPage.Create, R.drawable.lucide_calendar_plus, PanelAppAction.CreateEvent, R.string.panel_calendar_create_detail),
        appRow(HostToolPage.Calendar, R.drawable.lucide_calendar, PanelAppAction.ViewEvents, R.string.panel_calendar_view_detail),
        appRow(HostToolPage.Browser, R.drawable.lucide_app_window, PanelAppAction.Browser, R.string.panel_browser_detail)
    )
    @Composable fun matches(title: String, entries: List<Entry>): List<Entry> {
        val labels = entries.associate { it.page to stringResource(it.page.label) }
        return matchingToolChoices(query, title, entries) { labels.getValue(it.page) + " " + it.detail.orEmpty() }
    }
    val workTitle = stringResource(R.string.panel_work)
    val work = matches(workTitle, envRows)
    val browsing = query.isEmpty()
    val localTitle = stringResource(R.string.panel_local)
    val local = matches(localTitle, localRows)
    val choices = state.assistants.find { it.id == chat.assistantId }?.manualPanelTools.orEmpty()
    val active = localRows.count { canUse && it.enabled && it.page.manualTool in choices }
    PanelSection("local", localTitle, "$active/${localRows.size}", local.isNotEmpty(), false, !browsing,
        summaryDescription = stringResource(R.string.panel_manual_only, active, localRows.size)) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            for (entry in local) {
                val tool = entry.page.manualTool
                val available = entry.enabled && canUse && tool != null
                val selected = available && tool in choices
                val title = stringResource(entry.page.label)
                PanelToolRow(title, entry.icon, entry.detail.takeUnless { browsing && available }, selected, entry.page.name,
                    available = available, highlight = selected, chevron = false, controls = {
                        val switchLabel = if (tool == ManualPanelTool.AskUser) stringResource(R.string.ask_switch) else stringResource(R.string.panel_switch, title)
                        ManualPanelSwitch(selected, available, switchLabel, entry.page.name) { on ->
                            if (tool != null) vm.setManualPanelTool(chat.assistantId, tool, on)
                        }
                    }) { show(entry.page) }
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
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (showManage && servers.isEmpty()) {
                val status = when {
                    mcp == null -> R.string.panel_loading
                    mcp.isFailure -> R.string.feature_unavailable
                    else -> R.string.panel_no_servers
                }
                Text(stringResource(status), Modifier.padding(start = 42.dp, end = 12.dp, top = 6.dp, bottom = 6.dp),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            for (server in serverMatches) {
                val local = server.definition.endpoint == null
                val selectable = local && server.trusted && canUse
                val detail = when {
                    !local -> R.string.mcp_config_only
                    !server.trusted -> R.string.panel_untrusted
                    else -> R.string.mcp_in_process
                }
                val toolCount = if (local) {
                    val count = LocalMcpFixture.toolNames.size
                    pluralStringResource(R.plurals.panel_tool_count, count, count)
                } else null
                PanelToolRow(server.definition.name, R.drawable.lucide_server,
                    stringResource(detail).takeUnless { browsing && selectable }, canUse && !saving, server.id,
                    value = toolCount, checked = server.enabled.takeIf { selectable }) {
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
            if (showManage) PanelToolRow(manageTitle, R.drawable.lucide_settings2, enabled = canUse, id = "Mcp",
                value = state.assistants.find { it.id == chat.assistantId }?.name) { show(HostToolPage.Mcp) }
        }
    }
    PanelSection("environment", workTitle, selectedName, work.isNotEmpty(), true, !browsing, summaryOnCollapseOnly = true) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            for (entry in work) {
                val page = entry.page
                val title = stringResource(page.label)
                if (browsing && page == HostToolPage.Environment) {
                    PanelToolRow(selectedName, entry.icon, enabled = entry.enabled, id = page.name,
                        chevron = false, compactActions = true, controls = {
                            for (action in envRows.filter { it.page == HostToolPage.Files || it.page == HostToolPage.Terminal ||
                                (env != null && it.page == HostToolPage.Settings) }) {
                                InlinePanelAction(stringResource(action.page.label), action.icon, action.enabled, action.page.name) { show(action.page) }
                            }
                        }) { show(page) }
                } else if (!browsing || page !in setOf(HostToolPage.Settings, HostToolPage.Files, HostToolPage.Terminal)) {
                    val explanation = if (page == HostToolPage.Permissions)
                        stringResource(if (denied) R.string.panel_permission_deny_detail else R.string.panel_permission_ask_detail)
                    else null
                    PanelToolRow(title, entry.icon, explanation, entry.enabled, page.name,
                        value = entry.detail) { show(page) }
                }
            }
        }
    }
    if (!legacyMatches && work.isEmpty() && local.isEmpty() && !mcpVisible) {
        Text(stringResource(R.string.chat_tools_empty), Modifier.fillMaxWidth().testTag("chat-tools-empty")
            .semantics { liveRegion = LiveRegionMode.Polite }.padding(8.dp), style = MaterialTheme.typography.bodyMedium)
    }
    destination?.let { page ->
        key(chat.id, chat.environmentId, page) {
            MaterialTheme(colorScheme = toolControlScheme()) {
                HostToolDestination(page, vm, chat, catalog?.getOrNull(), env, onSettings = { show(HostToolPage.Settings) }) {
                    destination = null
                    if (page in listOf(HostToolPage.Mcp, HostToolPage.Settings, HostToolPage.Path, HostToolPage.Environment)) revision++
                }
            }
        }
    }
}
