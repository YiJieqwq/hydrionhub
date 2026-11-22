package io.hydrion.hub.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.hydrion.hub.R
import io.hydrion.hub.model.*
import io.hydrion.hub.provider.ModelCatalog
import io.hydrion.hub.provider.ModelSelectionSheet
import io.hydrion.hub.provider.route
import io.hydrion.hub.ui.sidebar.AssistantAvatar
import kotlinx.coroutines.launch

internal enum class AssistantTab(val label: Int) {
    Basic(R.string.assistant_basic), Prompt(R.string.assistant_prompts), Memory(R.string.settings_memory),
    Skills(R.string.settings_skill), Mcp(R.string.settings_mcp)
}

@Composable
internal fun AssistantDetail(vm: HubViewModel, id: String, close: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    val load by vm.workspaceLoad.collectAsStateWithLifecycle()
    val owner = state.assistants.find { it.id == id }
    if (load != WorkspaceLoad.Ready || owner == null) {
        if (load != WorkspaceLoad.Reading) LaunchedEffect(id) { close() }
        return
    }
    val catalog by vm.providers.catalog.collectAsStateWithLifecycle()
    key(id) {
        AssistantEditor(owner, catalog, save = { draft ->
            vm.updateAssistant(id, draft.name, draft.avatarText, draft.systemPrompt, draft.chatModel, draft.contextLimit)
        }, close = close) { tab ->
            when (tab) {
                AssistantTab.Memory -> MemorySettings(vm, close, id)
                AssistantTab.Skills -> SkillSettings(vm, close, id)
                AssistantTab.Mcp -> McpSettings(vm, close, id)
                else -> error("Unexpected assistant tool tab")
            }
        }
    }
}

@Composable
internal fun AssistantEditor(
    owner: LocalAssistant, catalog: ModelCatalog, save: (LocalAssistant) -> Boolean, close: () -> Unit,
    toolPage: @Composable (AssistantTab) -> Unit,
) {
    var name by rememberSaveable { mutableStateOf(owner.name) }
    var avatar by rememberSaveable { mutableStateOf(owner.avatarText) }
    var prompt by rememberSaveable { mutableStateOf(owner.systemPrompt) }
    var providerId by rememberSaveable { mutableStateOf(owner.chatModel?.providerId) }
    var modelId by rememberSaveable { mutableStateOf(owner.chatModel?.modelId) }
    var limitEnabled by rememberSaveable { mutableStateOf(owner.contextLimit != null) }
    var limitText by rememberSaveable { mutableStateOf((owner.contextLimit ?: 64).toString()) }
    val limit = if (limitEnabled) limitText.toIntOrNull() else null
    val validLimit = !limitEnabled || (limit != null && limit in LocalAssistant.ContextLimits)
    val selected = providerId?.let { p -> modelId?.let { m -> RemoteModel(p, m) } }
    val dirty = name.trim() != owner.name || avatar.trim() != owner.avatarText || prompt != owner.systemPrompt || selected != owner.chatModel || limit != owner.contextLimit || !validLimit
    var discard by rememberSaveable { mutableStateOf(false) }
    var editingAvatar by rememberSaveable { mutableStateOf(false) }
    var picking by rememberSaveable { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    val entries = remember(catalog) { catalog.entries(Mode.Chat) }
    val limitLabel = stringResource(R.string.assistant_limit)
    val pager = rememberPagerState { AssistantTab.entries.size }
    val tabs = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val back: () -> Unit = { if (dirty) discard = true else close() }
    LaunchedEffect(pager.currentPage) {
        if (tabs.layoutInfo.visibleItemsInfo.none { it.index == pager.currentPage }) tabs.animateScrollToItem(pager.currentPage)
    }
    Dialog(onDismissRequest = back, properties = DialogProperties(usePlatformDefaultWidth = false,
        decorFitsSystemWindows = false, dismissOnClickOutside = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding().testTag("assistant-detail-" + owner.id)) {
                Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(back) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.settings_back)) }
                    Text(owner.name, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleLarge)
                    TextButton({
                        val draft = owner.copy(name = name.trim(), avatarText = avatar.trim(), systemPrompt = prompt, chatModel = selected, contextLimit = limit)
                        if (save(draft)) close() else failed = true
                    }, enabled = dirty && validLimit && LocalAssistant.validName(name.trim()) && LocalAssistant.validAvatar(avatar.trim()) && LocalAssistant.validPrompt(prompt)) {
                        Text(stringResource(R.string.settings_save))
                    }
                }
                val colors = MaterialTheme.colorScheme
                BoxWithConstraints(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 2.dp, bottom = 8.dp)) {
                    val n = AssistantTab.entries.size
                    val width = maxOf(88.dp, (maxWidth - 8.dp - 6.dp * (n - 1)) / n)
                    LazyRow(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(colors.surfaceContainer),
                        state = tabs, horizontalArrangement = Arrangement.spacedBy(6.dp), contentPadding = PaddingValues(4.dp)) {
                        items(AssistantTab.entries.size) { i ->
                            val active = pager.currentPage == i
                            Box(Modifier.width(width).clip(RoundedCornerShape(14.dp))
                                .then(if (active) Modifier.background(colors.primary.copy(alpha = .14f)) else Modifier)
                                .selectable(active, role = Role.Tab, onClick = { scope.launch { pager.animateScrollToPage(i) } })
                                .heightIn(min = 36.dp).padding(horizontal = 6.dp), contentAlignment = Alignment.Center) {
                                Text(stringResource(AssistantTab.entries[i].label), fontSize = 14.sp,
                                    color = if (active) colors.primary else colors.onSurface.copy(alpha = .82f))
                            }
                        }
                    }
                }
                if (failed) Text(stringResource(R.string.assistant_save_failed), color = colors.error, modifier = Modifier.padding(horizontal = 16.dp))
                HorizontalPager(pager, Modifier.fillMaxWidth().weight(1f), verticalAlignment = Alignment.Top) { i ->
                    when (AssistantTab.entries[i]) {
                        AssistantTab.Basic -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
                            verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            DetailCard {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    val avatarLabel = stringResource(R.string.assistant_avatar_text)
                                    Box(Modifier.clip(androidx.compose.foundation.shape.CircleShape)
                                        .clickable(role = Role.Button) { editingAvatar = true }.semantics { contentDescription = avatarLabel }) {
                                        AssistantAvatar(owner.copy(name = name.trim().ifEmpty { owner.name }, avatarText = avatar), 64.dp)
                                    }
                                    Spacer(Modifier.width(14.dp))
                                    OutlinedTextField(name, { name = it.take(64) }, Modifier.weight(1f), singleLine = true,
                                        label = { Text(stringResource(R.string.assistant_name)) })
                                }
                            }
                            DetailCard {
                                Row(Modifier.fillMaxWidth().clickable(role = Role.Button) { picking = true }.heightIn(min = 48.dp),
                                    verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text(stringResource(R.string.assistant_model_default), fontSize = 15.sp)
                                        val entry = entries.find { it.route == selected }
                                        Text(entry?.let { (m, p) -> m.name + " · " + p.name } ?: stringResource(
                                            if (selected == null) R.string.assistant_follow_global else R.string.assistant_model_unavailable),
                                            fontSize = 13.sp, color = colors.onSurfaceVariant)
                                    }
                                    Icon(painterResource(R.drawable.lucide_chevron_right), null, Modifier.size(18.dp))
                                }
                                if (selected != null) TextButton({ providerId = null; modelId = null }) { Text(stringResource(R.string.assistant_follow_global)) }
                                Text(stringResource(R.string.assistant_model_precedence), style = MaterialTheme.typography.bodySmall,
                                    color = colors.onSurfaceVariant)
                            }
                            DetailCard {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(stringResource(R.string.assistant_limit), Modifier.weight(1f))
                                    Switch(limitEnabled, { limitEnabled = it }, modifier = Modifier.semantics {
                                        contentDescription = limitLabel
                                    })
                                }
                                if (limitEnabled) OutlinedTextField(limitText, { value ->
                                    if (value.length <= 4 && value.all(Char::isDigit)) limitText = value
                                }, Modifier.fillMaxWidth(), singleLine = true, isError = !validLimit,
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                    label = { Text(stringResource(R.string.assistant_limit_range)) })
                            }
                        }
                        AssistantTab.Prompt -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
                            DetailCard {
                                Text(stringResource(R.string.assistant_persona), style = MaterialTheme.typography.titleMedium)
                                OutlinedTextField(prompt, { if (LocalAssistant.validPrompt(it)) prompt = it }, Modifier.fillMaxWidth()
                                    .testTag("assistant-system-prompt"), minLines = 5, maxLines = 12)
                                Text(stringResource(R.string.assistant_persona_help), style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        else -> toolPage(AssistantTab.entries[i])
                    }
                }
            }
        }
        if (editingAvatar) {
            var value by rememberSaveable { mutableStateOf(avatar) }
            AlertDialog(onDismissRequest = { editingAvatar = false }, title = { Text(stringResource(R.string.assistant_avatar_text)) },
                text = { OutlinedTextField(value, { if (LocalAssistant.validAvatar(it)) value = it }, singleLine = true) },
                confirmButton = { TextButton({ avatar = value.trim(); editingAvatar = false }) { Text(stringResource(R.string.settings_save)) } },
                dismissButton = { TextButton({ editingAvatar = false }) { Text(stringResource(R.string.settings_cancel)) } })
        }
        if (picking) ModelSelectionSheet(entries, selected, onDismiss = { picking = false }) {
            providerId = it.providerId; modelId = it.modelId; picking = false
        }
        if (discard) AlertDialog(onDismissRequest = { discard = false }, text = { Text(stringResource(R.string.assistant_discard)) },
            confirmButton = { TextButton(close) { Text(stringResource(R.string.model_settings_drop)) } },
            dismissButton = { TextButton({ discard = false }) { Text(stringResource(R.string.settings_cancel)) } })
    }
}

@Composable
private fun DetailCard(content: @Composable ColumnScope.() -> Unit) {
    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
    }
}
