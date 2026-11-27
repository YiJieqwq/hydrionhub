package io.hydrion.hub.provider

import androidx.compose.ui.res.stringResource
import io.hydrion.hub.R
import io.hydrion.hub.ui.theme.HubShapes

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

private fun Set<String>.toggled(key: String) = if (key in this) this - key else this + key

@Composable
internal fun ModelBasicTab(draft: ModelEditDraft) {
    val modelTypes = listOf(ModelType.Chat to stringResource(R.string.model_type_chat), ModelType.Embedding to stringResource(R.string.model_type_embed), ModelType.Image to stringResource(R.string.model_type_image))
    val inputs = listOf("text" to stringResource(R.string.model_io_text), "image" to stringResource(R.string.model_io_image), "audio" to stringResource(R.string.model_io_audio), "video" to stringResource(R.string.model_io_video), "pdf" to "PDF")
    val outputs = inputs.take(3)
    val abilities = listOf("tools" to stringResource(R.string.model_tools), "reasoning" to stringResource(R.string.model_tab_reasoning), "structured" to stringResource(R.string.model_structured))
    var iconPicker by remember { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    val profile = draft.profile
    ModelGroup("") {
        Row(Modifier.fillMaxWidth().clickable { iconPicker = true }.heightIn(min = 48.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Surface(shape = HubShapes.Small, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                ProfileIcon(profile.icon, draft.type, Modifier.padding(9.dp).size(22.dp))
            }
            Text(stringResource(R.string.model_icon), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null, Modifier.size(18.dp))
        }
    }
    ModelField(stringResource(R.string.model_id), profile.externalId, { draft.profile = profile.copy(externalId = it) },
        trailingIcon = { IconButton(enabled = profile.externalId.isNotBlank(), onClick = {
            clipboard.setText(AnnotatedString(profile.externalId))
        }) { Icon(Icons.Outlined.ContentCopy, stringResource(R.string.model_copy_id), Modifier.size(19.dp)) } })
    ModelField(stringResource(R.string.model_name), draft.name, { draft.name = it })
    if (draft.type == ModelType.Chat) {
        val preset = defaultContextWindowTokens(profile.externalId.ifBlank { draft.name })
        ModelField(stringResource(R.string.model_window), draft.contextWindow, { draft.contextWindow = it },
            keyboard = KeyboardType.Number, placeholder = stringResource(R.string.model_window_preset, preset))
    }
    ModelGroup(stringResource(R.string.model_type)) {
        Row(Modifier.fillMaxWidth().selectableGroup(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            modelTypes.forEach { (type, label) ->
                val active = type == draft.type
                Surface(Modifier.weight(1f), shape = HubShapes.Small,
                    color = if (active) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer) {
                    Box(Modifier.selectable(active, role = Role.RadioButton, onClick = { draft.changeType(type) })
                        .heightIn(min = 39.dp), contentAlignment = Alignment.Center) {
                        Text(label, color = if (active) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface)
                    }
                }
            }
        }
    }
    ModelGroup(stringResource(R.string.model_input)) {
        ModelChips(inputs, profile.input) { draft.profile = profile.copy(input = profile.input.toggled(it)) }
    }
    ModelGroup(stringResource(R.string.model_output)) {
        ModelChips(outputs, profile.output) { draft.profile = profile.copy(output = profile.output.toggled(it)) }
    }
    ModelGroup(stringResource(R.string.model_abilities)) {
        ModelChips(abilities, profile.abilities) { key ->
            val selected = profile.abilities.toggled(key)
            val levels = if (key == "reasoning" && key in selected && profile.levels.isEmpty()) listOf("Low", "Medium", "High") else profile.levels
            draft.profile = profile.copy(abilities = selected, levels = levels)
        }
    }
    if (draft.type != ModelType.Embedding) ModelGroup("") {
        ModelToggle(if (draft.currentDefault) stringResource(R.string.model_current_default) else stringResource(R.string.model_make_default), draft.makeDefault,
            enabled = !draft.currentDefault) { draft.makeDefault = it }
    }
    if (iconPicker) {
        AlertDialog(onDismissRequest = { iconPicker = false }, title = { Text(stringResource(R.string.model_select_icon)) },
            text = { Column {
                (listOf("" to stringResource(R.string.model_auto)) + profileIcons.map { it.first to stringResource(it.second) }).forEach { (key, title) ->
                    Row(Modifier.fillMaxWidth().selectable(key == profile.icon, role = Role.RadioButton, onClick = {
                        draft.profile = profile.copy(icon = key)
                        iconPicker = false
                    }).heightIn(min = 42.dp), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        ProfileIcon(key, draft.type, Modifier.size(20.dp))
                        Text(title, Modifier.weight(1f))
                        if (key == profile.icon) Icon(Icons.Outlined.Check, null, Modifier.size(17.dp))
                    }
                }
            } }, confirmButton = { TextButton(onClick = { iconPicker = false }) { Text(stringResource(R.string.settings_cancel)) } })
    }
}
