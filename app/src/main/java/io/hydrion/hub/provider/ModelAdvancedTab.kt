package io.hydrion.hub.provider

import androidx.compose.ui.res.stringResource
import io.hydrion.hub.R

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.LocalSaveableStateRegistry
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import java.util.UUID

@Composable
internal fun ModelAdvancedTab(
    profile: ModelProfile,
    budget: String,
    imageBudget: String,
    headers: List<HeaderDraft>,
    onProfile: (ModelProfile) -> Unit,
    onBudget: (String) -> Unit,
    onImageBudget: (String) -> Unit,
    onHeaders: (List<HeaderDraft>) -> Unit
) {
    var editingBudget by rememberSaveable { mutableStateOf(false) }
    var editingBody by rememberSaveable { mutableStateOf(false) }
    val overrideBudget = imageBudget.isNotBlank() || budget.isNotBlank()
    ModelGroup("") {
        AdvancedAction(stringResource(R.string.model_budget), if (overrideBudget) stringResource(R.string.model_custom) else stringResource(R.string.model_inherit)) { editingBudget = true }
    }
    ModelGroup(stringResource(R.string.model_request_config)) {
        headers.forEachIndexed { index, header ->
            key(header.id) {
                HeaderEditor(index + 1, header, update = { edited ->
                    onHeaders(headers.map { if (it.id == header.id) edited else it })
                }, remove = { onHeaders(headers.filterNot { it.id == header.id }) })
            }
        }
        TextButton(onClick = { onHeaders(headers + HeaderDraft(UUID.randomUUID().toString())) }) {
            Icon(Icons.Outlined.Add, null, Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.model_add_header))
        }
        if (profile.requestBody.isEmpty()) {
            TextButton(onClick = { editingBody = true }) {
                Icon(Icons.Outlined.Add, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.model_add_body))
            }
        } else {
            AdvancedAction(stringResource(R.string.model_json_body), stringResource(R.string.model_edit_json)) { editingBody = true }
        }
    }
    ModelGroup(stringResource(R.string.model_image_input)) {
        ModelToggle(stringResource(R.string.model_remote_images), profile.imageLinks) { onProfile(profile.copy(imageLinks = it)) }
    }
    ModelGroup(stringResource(R.string.model_builtin_tools)) {
        ModelToggle(stringResource(R.string.model_interpreter), profile.codeInterpreter) { onProfile(profile.copy(codeInterpreter = it)) }
    }
    if (editingBudget) {
        BudgetDialog(imageBudget, budget, onDismiss = { editingBudget = false },
            onApply = { imageLimit, requestLimit ->
                onImageBudget(imageLimit)
                onBudget(requestLimit)
                editingBudget = false
            })
    }
    if (editingBody) {
        BodyDialog(profile.requestBody, onDismiss = { editingBody = false }, onApply = { json ->
            onProfile(profile.copy(requestBody = json))
            editingBody = false
        })
    }
}

@Composable
private fun AdvancedAction(label: String, detail: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).heightIn(min = 48.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Text(detail, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null, Modifier.size(18.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun HeaderEditor(index: Int, header: HeaderDraft, update: (HeaderDraft) -> Unit, remove: () -> Unit) {
    var revealed by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.model_header_number, index), Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
            IconButton(remove) { Icon(Icons.Outlined.Close, stringResource(R.string.model_remove_header, index), Modifier.size(18.dp)) }
        }
        ModelField(stringResource(R.string.model_header_name), header.key, { update(header.copy(key = it)) })
        CompositionLocalProvider(LocalSaveableStateRegistry provides null) {
            OutlinedTextField(header.value, { update(header.copy(value = it)) }, singleLine = true,
                modifier = Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.model_header_value)) }, shape = RoundedCornerShape(12.dp),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                visualTransformation = if (revealed) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    IconButton(onClick = { revealed = !revealed }) {
                        Icon(if (revealed) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                            if (revealed) stringResource(R.string.model_hide_header) else stringResource(R.string.model_show_header), Modifier.size(19.dp))
                    }
                })
        }
    }
}

@Composable
private fun BudgetDialog(images: String, requests: String, onDismiss: () -> Unit, onApply: (String, String) -> Unit) {
    var imageLimit by rememberSaveable { mutableStateOf(images) }
    var requestLimit by rememberSaveable { mutableStateOf(requests) }
    fun isLimitValid(value: String) = value.isBlank() || value.trim().toIntOrNull()?.let { it >= 0 } == true
    val valid = isLimitValid(imageLimit) && isLimitValid(requestLimit)
    AlertDialog(onDismissRequest = onDismiss, title = { Text(stringResource(R.string.model_budget)) }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            ModelField(stringResource(R.string.model_image_limit), imageLimit, { imageLimit = it }, keyboard = KeyboardType.Number)
            ModelField(stringResource(R.string.model_request_limit), requestLimit, { requestLimit = it }, keyboard = KeyboardType.Number)
            if (!valid) Text(stringResource(R.string.model_invalid_budget), color = MaterialTheme.colorScheme.error)
            else Text(stringResource(R.string.model_inherit_help), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = { onApply("", "") }) { Text(stringResource(R.string.model_restore_inherited)) }
        }
    }, dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.settings_cancel)) } },
        confirmButton = { TextButton(enabled = valid, onClick = {
            onApply(imageLimit.trim(), requestLimit.trim())
        }) { Text(stringResource(R.string.model_done)) } })
}

@Composable
private fun BodyDialog(initial: String, onDismiss: () -> Unit, onApply: (String) -> Unit) {
    var json by rememberSaveable { mutableStateOf(initial.ifBlank { "{}" }) }
    var error by remember { mutableStateOf<Int?>(null) }
    val jsonLabel = stringResource(R.string.model_json_accessible)
    AlertDialog(onDismissRequest = onDismiss, title = { Text(stringResource(R.string.model_json_body)) }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(json, { json = it; error = null }, modifier = Modifier.fillMaxWidth().semantics {
                contentDescription = jsonLabel
            }, minLines = 4, maxLines = 8, label = { Text(stringResource(R.string.model_json_object)) }, shape = RoundedCornerShape(12.dp))
            error?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            if (initial.isNotEmpty()) TextButton(onClick = { onApply("") }) { Text(stringResource(R.string.model_delete_body)) }
        }
    }, dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.settings_cancel)) } },
        confirmButton = { TextButton(onClick = {
            val raw = json.trim()
            error = if (raw.isEmpty()) null else RequestTemplate.preview(raw, null).exceptionOrNull()?.let {
                R.string.model_invalid_json
            }
            if (error == null) onApply(raw)
        }) { Text(stringResource(R.string.model_done)) } })
}
