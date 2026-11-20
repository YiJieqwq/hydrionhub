package io.hydrion.hub.provider

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import io.hydrion.hub.model.Mode

@Composable
internal fun ModelEditor(
    mode: Mode, initial: ProviderModel, exists: Boolean, isDefault: Boolean,
    otherModels: List<ProviderModel>,
    onBack: () -> Unit, onSave: (ProviderModel, Boolean) -> Unit, onDelete: () -> Unit
) {
    val owner = viewModel<ModelFormOwner>()
    val draft = remember(initial.id, owner) { owner.open(initial, mode, isDefault) }
    ModelEditor(draft, exists, otherModels,
        onBack = { owner.close(); onBack() },
        onSave = { model, makeDefault -> owner.close(); onSave(model, makeDefault) },
        onDelete = { owner.close(); onDelete() })
}

@Composable
internal fun ModelEditor(
    draft: ModelEditDraft, exists: Boolean, otherModels: List<ProviderModel>,
    onBack: () -> Unit, onSave: (ProviderModel, Boolean) -> Unit, onDelete: () -> Unit,
    fadeScrollEdges: Boolean = false,
) {
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    var discard by rememberSaveable { mutableStateOf(false) }
    var deleting by rememberSaveable { mutableStateOf(false) }
    val focus = LocalFocusManager.current
    val pageStates = rememberSaveableStateHolder()
    val exit = { focus.clearFocus(); onBack() }
    val requestExit = { if (draft.dirty) discard = true else exit() }
    val save = {
        val problem = draft.error(otherModels)
        if (problem != null) {
            draft.page = problem.first
            error = problem.second
        } else {
            val edited = draft.buildModel()
            val default = draft.type != ModelType.Embedding && draft.makeDefault
            focus.clearFocus()
            onSave(edited, default)
        }
    }
    BackHandler(onBack = requestExit)
    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = requestExit) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回") }
        Text(if (exists) "编辑模型" else "添加模型", Modifier.weight(1f),
            style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        IconButton(onClick = save) { Icon(Icons.Outlined.Check, "保存模型", tint = MaterialTheme.colorScheme.primary) }
    }
    Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Row(Modifier.fillMaxWidth().selectableGroup().padding(4.dp)) {
            ModelPage.entries.forEach { item ->
                val selected = item == draft.page
                Surface(Modifier.weight(1f), shape = RoundedCornerShape(11.dp),
                    color = if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent) {
                    Box(Modifier.selectable(selected = selected, role = Role.Tab, onClick = {
                        focus.clearFocus()
                        draft.page = item
                    }).heightIn(min = 38.dp), contentAlignment = Alignment.Center) {
                        Text(item.title, color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
                    }
                }
            }
        }
    }
    error?.let { Text(it, Modifier.padding(top = 8.dp), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
    pageStates.SaveableStateProvider(draft.page.name) {
        val scroll = rememberScrollState()
        val edges = if (fadeScrollEdges) Modifier.modelConfigEdgeFades(scroll, MaterialTheme.colorScheme.background) else Modifier
        Column(Modifier.fillMaxWidth().then(edges).verticalScroll(scroll).padding(vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)) {
            when (draft.page) {
                ModelPage.Basic -> ModelBasicTab(draft)
                ModelPage.Reasoning -> ModelReasoningTab(draft.profile) { draft.profile = it; error = null }
                ModelPage.Advanced -> {
                    ModelAdvancedTab(draft.profile, draft.budget, draft.imageBudget, draft.headers,
                        onProfile = { draft.profile = it; error = null }, onBudget = { draft.budget = it },
                        onImageBudget = { draft.imageBudget = it }, onHeaders = { draft.headers = it })
                    ConfigurableParameters(draft)
                    if (exists) TextButton(onClick = { deleting = true }, modifier = Modifier.fillMaxWidth()) {
                        Text("删除模型", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }
    if (discard) DiscardChanges(onKeep = { discard = false }, onDiscard = exit)
    if (deleting) ConfirmRemoval("从供应商移除“${draft.initial.name}”？", { deleting = false }) {
        focus.clearFocus()
        onDelete()
    }
}

@Composable
private fun ConfigurableParameters(draft: ModelEditDraft) {
    ModelGroup("可配置参数") {
        draft.builtinOptions().forEach { option ->
            val selected = option.key in draft.checked
            ModelToggle(if (option.key == "speed") "响应速度" else option.label, selected) {
                draft.checked = if (it) draft.checked + option.key else draft.checked - option.key
            }
        }
        draft.custom.forEach { field ->
            key(field.id) {
                val update: (CapabilityDraft) -> Unit = { new ->
                    draft.custom = draft.custom.map { if (it.id == field.id) new else it }
                }
                ModelField("字段名", field.key, { update(field.copy(key = it)) })
                ModelField("显示名称", field.label, { update(field.copy(label = it)) })
                ModelField("可选值（逗号分隔，首项为默认值）", field.values, { update(field.copy(values = it)) })
                TextButton(onClick = { draft.custom = draft.custom.filterNot { it.id == field.id } }) { Text("移除参数") }
            }
        }
        TextButton(onClick = { draft.custom = draft.custom + CapabilityDraft() }) { Text("添加自定义参数") }
    }
}
