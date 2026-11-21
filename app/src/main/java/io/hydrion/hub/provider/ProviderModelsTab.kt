package io.hydrion.hub.provider

import androidx.compose.ui.res.stringResource
import io.hydrion.hub.R

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.PlaylistAdd
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.hydrion.hub.model.Mode
import io.hydrion.hub.ui.theme.IonMark
import io.hydrion.hub.ui.theme.capsuleEdge
import java.util.UUID

@Composable
internal fun ProviderModelsTab(
    provider: Provider,
    defaultIds: Set<String>,
    onModelsChange: (List<ProviderModel>) -> Unit,
    onEdit: (ProviderModel) -> Unit,
    modifier: Modifier = Modifier
) {
    var choosing by rememberSaveable(provider.id) { mutableStateOf(false) }
    Box(modifier.fillMaxSize()) {
        if (provider.models.isEmpty()) {
            Text(stringResource(R.string.model_none_added), Modifier.align(Alignment.Center),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(top = 6.dp, bottom = 84.dp)) {
                items(provider.models, key = { it.id }) { model ->
                    val edit = { onEdit(model) }
                    ModelLine(model, provider.mode, model.id in defaultIds, Modifier.clickable(onClick = edit)) {
                        IconButton(onClick = edit) {
                            Icon(Icons.Outlined.Tune, stringResource(R.string.model_configure_named, model.name), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }

        Surface(
            modifier = Modifier.align(Alignment.BottomCenter).padding(horizontal = 8.dp, vertical = 12.dp).capsuleEdge(),
            shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainerHigh
        ) {
            Row(Modifier.padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { choosing = true }) {
                    Icon(Icons.AutoMirrored.Outlined.PlaylistAdd, null, Modifier.size(19.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.chat_controls_select_model))
                }
                VerticalDivider(Modifier.height(18.dp), color = MaterialTheme.colorScheme.outlineVariant)
                TextButton(onClick = { onEdit(ProviderModel(UUID.randomUUID().toString(), "")) }) {
                    Icon(Icons.Outlined.Add, null, Modifier.size(19.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.model_add))
                }
            }
        }
    }
    if (choosing) LocalModelPicker(provider, defaultIds, onModelsChange) { choosing = false }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ModelLine(
    model: ProviderModel,
    mode: Mode,
    isDefault: Boolean,
    modifier: Modifier = Modifier,
    action: @Composable () -> Unit
) {
    Row(
        modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        val type = model.typeFor(mode)
        if (model.profile.icon.isBlank()) IonMark(36.dp)
        else Surface(modifier = Modifier.capsuleEdge(), shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
            Box(Modifier.size(36.dp), contentAlignment = Alignment.Center) {
                ProfileIcon(model.profile.icon, type, Modifier.size(21.dp))
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(model.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                ModelTag(type.name, primary = true)
                modelBadges(model).forEach { ModelTag(it) }
                if (isDefault) ModelTag(stringResource(R.string.model_default))
            }
        }
        action()
    }
}

@Composable
private fun ModelTag(label: String, primary: Boolean = false) {
    val colors = MaterialTheme.colorScheme
    val background = if (primary) colors.primaryContainer else colors.surfaceContainerHigh
    val foreground = if (primary) colors.onPrimaryContainer else colors.onSurfaceVariant
    Text(label, Modifier.background(background, RoundedCornerShape(5.dp)).padding(horizontal = 6.dp, vertical = 2.dp),
        style = MaterialTheme.typography.labelSmall, color = foreground)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LocalModelPicker(
    provider: Provider,
    defaultIds: Set<String>,
    onModelsChange: (List<ProviderModel>) -> Unit,
    onDismiss: () -> Unit
) {
    var query by rememberSaveable { mutableStateOf("") }
    var showAvailable by rememberSaveable { mutableStateOf(true) }
    var showConfigured by rememberSaveable { mutableStateOf(true) }
    val choices = remember(provider, query) { localModelChoices(provider, query) }
    val colors = MaterialTheme.colorScheme

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = colors.surfaceContainerLow
    ) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.82f).padding(horizontal = 16.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.chat_controls_select_model), Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.model_done)) }
            }
            Text(if (provider.id == DEEPSEEK_PROVIDER_ID) stringResource(R.string.model_deepseek_presets) else stringResource(R.string.model_local_catalog), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = query, onValueChange = { query = it }, singleLine = true,
                modifier = Modifier.fillMaxWidth(), placeholder = { Text(stringResource(R.string.model_filter_capability)) },
                leadingIcon = { Icon(Icons.Outlined.Search, null) },
                trailingIcon = if (query.isEmpty()) null else ({
                    IconButton(onClick = { query = "" }) { Icon(Icons.Outlined.Close, stringResource(R.string.model_clear_query)) }
                }),
                shape = RoundedCornerShape(18.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = colors.surfaceContainer, unfocusedContainerColor = colors.surfaceContainer,
                    unfocusedBorderColor = Color.Transparent
                )
            )
            Spacer(Modifier.height(8.dp))
            LazyColumn(Modifier.fillMaxWidth().weight(1f), contentPadding = PaddingValues(bottom = 16.dp)) {
                if (choices.available.isNotEmpty()) {
                    item(key = "available-header") {
                        GroupHeader(stringResource(R.string.model_not_added), choices.available.size, showAvailable, { showAvailable = !showAvailable }) {
                            TextButton(onClick = {
                                onModelsChange(addLocalModels(provider, choices.available.map { it.id }.toSet()))
                            }) { Text(if (query.isBlank()) stringResource(R.string.model_add_all) else stringResource(R.string.model_add_matches)) }
                        }
                    }
                    if (showAvailable) items(choices.available, key = { "available/${it.id}" }) { model ->
                        ModelLine(model, provider.mode, false) {
                            IconButton(onClick = { onModelsChange(addLocalModels(provider, setOf(model.id))) }) {
                                Icon(Icons.Outlined.Add, stringResource(R.string.model_add_named, model.name), tint = colors.primary)
                            }
                        }
                    }
                }
                if (choices.configured.isNotEmpty()) {
                    item(key = "configured-header") {
                        GroupHeader(stringResource(R.string.model_added), choices.configured.size, showConfigured, { showConfigured = !showConfigured })
                    }
                    if (showConfigured) items(choices.configured, key = { "configured/${it.id}" }) { model ->
                        ModelLine(model, provider.mode, model.id in defaultIds) {
                            Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                                Icon(Icons.Outlined.Check, stringResource(R.string.model_added_named, model.name), Modifier.size(18.dp), tint = colors.onSurfaceVariant)
                            }
                        }
                    }
                }
                if (choices.available.isEmpty() && choices.configured.isEmpty()) {
                    item { Text(stringResource(R.string.chat_picker_search_empty), Modifier.padding(vertical = 24.dp),
                        style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant) }
                }
            }
        }
    }
}

@Composable
private fun GroupHeader(
    title: String,
    count: Int,
    expanded: Boolean,
    onToggle: () -> Unit,
    trailing: @Composable (() -> Unit)? = null
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.weight(1f).clickable(role = Role.Button, onClick = onToggle)
            .heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(if (expanded) Icons.Outlined.ExpandMore else Icons.Outlined.ChevronRight,
                if (expanded) stringResource(R.string.model_collapse_section, title) else stringResource(R.string.model_expand_section, title), Modifier.size(20.dp))
            Text("$title · $count", style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        trailing?.invoke()
    }
}
