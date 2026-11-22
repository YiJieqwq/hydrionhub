package io.hydrion.hub.provider

import androidx.compose.ui.res.stringResource
import io.hydrion.hub.R

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.ViewInAr
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.hydrion.hub.model.Mode
import java.net.URI

@Composable
internal fun ProviderEditor(store: ProviderStore, edit: ProviderDraft, onBack: () -> Unit) {
    val draft = edit.provider
    val nestedModel = edit.editingModel
    val configScroll = rememberScrollState()
    val tabStates = rememberSaveableStateHolder()
    var error by rememberSaveable { mutableStateOf<Int?>(null) }
    var discard by rememberSaveable { mutableStateOf(false) }
    var delete by rememberSaveable { mutableStateOf(false) }
    val focus = LocalFocusManager.current
    val changeTab: (EditorTab) -> Unit = { next ->
        focus.clearFocus()
        edit.tab = next
    }
    val requestExit = { if (edit.dirty) discard = true else onBack() }
    val sample = remember(draft.models, edit.defaults) {
        val previewModel = draft.models.find { it.id == edit.defaults[draft.mode] } ?: draft.models.firstOrNull()
        RequestTemplate.sample(previewModel, draft.models)
    }
    val save = {
        val cleaned = draft.copy(name = draft.name.trim(), protocol = draft.protocol.trim(), baseUrl = draft.baseUrl.trim())
        error = validateProvider(cleaned, sample)
        if (error == null) {
            store.saveDefaults(cleaned, edit.apiKey, edit.defaults)
            focus.clearFocus()
            onBack()
        } else changeTab(EditorTab.Config)
    }
    Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding()) {
        if (nestedModel != null) {
            key(nestedModel.id) {
                Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
                    ModelEditor(draft.mode, nestedModel, draft.models.any { it.id == nestedModel.id },
                        isDefault = nestedModel.id in edit.defaults.values, otherModels = draft.models,
                        onBack = { edit.editingModel = null },
                        onSave = edit::finishModel,
                        onDelete = {
                            edit.replaceModels(draft.models.filterNot { it.id == nestedModel.id })
                            edit.editingModel = null
                        })
                }
            }
        } else {
            BackHandler(onBack = requestExit)
            Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(end = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = requestExit) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.settings_back)) }
                Text(if (draft.name.isBlank()) stringResource(R.string.model_settings_add_provider) else providerLabel(draft), Modifier.weight(1f),
                    style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (edit.dirty || !edit.existing) TextButton(onClick = save) { Text(stringResource(R.string.settings_save)) }
            }
            error?.let {
                Text(stringResource(it), Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            Box(Modifier.weight(1f)) {
                tabStates.SaveableStateProvider(edit.tab.name) {
                    when (edit.tab) {
                        EditorTab.Config -> ProviderConfigTab(draft, edit.apiKey, sample, configScroll, edit.existing,
                            onUpdate = { edit.provider = it; error = null },
                            onKey = { edit.apiKey = it },
                            onModels = { changeTab(EditorTab.Models) },
                            onDelete = { delete = true })
                        EditorTab.Models -> ProviderModelsTab(draft, edit.defaults.values.toSet(), edit::replaceModels,
                            onEdit = { edit.editingModel = it }, modifier = Modifier.fillMaxSize())
                    }
                }
            }
            EditorTabs(edit.tab, changeTab)
        }
    }
    if (discard) DiscardChanges(onKeep = { discard = false }, onDiscard = onBack)
    if (delete) ConfirmRemoval(stringResource(R.string.model_remove_provider_models, edit.initial.name), { delete = false }) {
        store.remove(edit.initial.id)
        onBack()
    }
}

internal fun validateProvider(provider: Provider, sample: Map<String, Any>): Int? {
    if (provider.name.isBlank()) return R.string.model_enter_provider
    if (provider.protocol.isBlank()) return R.string.model_enter_protocol
    if (provider.baseUrl.isNotEmpty()) {
        if (provider.chatProtocol() != null) {
            return provider.chatProtocolIssue()?.let { R.string.model_invalid_service }
        }
        val uri = runCatching { URI(provider.baseUrl) }.getOrNull()
        if (uri?.scheme?.lowercase() !in setOf("http", "https") || uri?.host.isNullOrBlank()) {
            return R.string.model_valid_url
        }
    }
    return if (provider.mode == Mode.Image) {
        RequestTemplate.preview(provider.requestBody, sample).exceptionOrNull()?.let { R.string.model_body_invalid }
    } else null
}

@Composable
private fun EditorTabs(selected: EditorTab, onTab: (EditorTab) -> Unit) {
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .5f))
    Row(Modifier.fillMaxWidth().selectableGroup()) {
        EditorTab.entries.forEach { tab ->
            val current = tab == selected
            val tint = if (current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
            Column(Modifier.weight(1f).selectable(selected = current, role = Role.Tab, onClick = { onTab(tab) })
                .heightIn(min = 62.dp).padding(vertical = 6.dp),
                verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(if (tab == EditorTab.Config) Icons.Outlined.Settings else Icons.Outlined.ViewInAr,
                    null, Modifier.size(22.dp), tint)
                Spacer(Modifier.height(2.dp))
                Text(if (tab == EditorTab.Config) stringResource(R.string.model_configure) else stringResource(R.string.chat_picker_models), color = tint,
                    fontWeight = if (current) FontWeight.SemiBold else FontWeight.Normal,
                    style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

@Composable
internal fun DiscardChanges(onKeep: () -> Unit, onDiscard: () -> Unit) {
    AlertDialog(onDismissRequest = onKeep, title = { Text(stringResource(R.string.model_settings_discard)) },
        dismissButton = { TextButton(onClick = onKeep) { Text(stringResource(R.string.model_settings_keep)) } },
        confirmButton = { TextButton(onClick = onDiscard) { Text(stringResource(R.string.model_settings_drop), color = MaterialTheme.colorScheme.error) } })
}
