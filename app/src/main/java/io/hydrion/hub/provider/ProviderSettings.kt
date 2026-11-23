package io.hydrion.hub.provider

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import io.hydrion.hub.ui.settings.MessageStyleSettings
import io.hydrion.hub.ui.settings.SettingsEntry
import io.hydrion.hub.ui.settings.SettingsHome
import io.hydrion.hub.ui.composer.DictationRmsDiagnostic
import androidx.compose.ui.res.stringResource
import io.hydrion.hub.R
import io.hydrion.hub.ui.theme.capsuleEdge
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import io.hydrion.hub.model.Mode
import java.util.UUID

internal fun newProvider(mode: Mode): Provider = Provider(
    id = UUID.randomUUID().toString(),
    mode = mode,
    name = "",
    protocol = if (mode == Mode.Chat) ChatProtocol.ChatCompletionsHttp.label else "OpenAI Images",
    baseUrl = "",
    models = emptyList(),
)

@Composable
fun ProviderSettings(store: ProviderStore, onBack: () -> Unit) {
    val catalog by store.catalog.collectAsState()
    val navigation = viewModel<ProviderNavigation>()
    val currentMode = navigation.mode
    val edit = navigation.edit
    var detail by rememberSaveable { mutableStateOf<SettingsEntry?>(null) }
    var search by rememberSaveable { mutableStateOf("") }
    val goBack = {
        when {
            detail != null -> detail = null
            currentMode != null -> navigation.mode = null
            else -> onBack()
        }
    }
    BackHandler(enabled = edit == null, onBack = goBack)
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        when {
            edit != null -> key(edit.initial.id) {
                ProviderEditor(store, edit, onBack = { navigation.edit = null })
            }
            currentMode != null -> ModelProviderPage(currentMode, catalog, goBack, onAdd = {
                navigation.open(newProvider(currentMode), store)
            }, onMove = store::moveChatProvider, onOpen = { navigation.open(it, store) })
            detail == SettingsEntry.Defaults -> DefaultModelsPage(store, catalog, goBack)
            detail == SettingsEntry.Search -> {
                val hub = viewModel<io.hydrion.hub.model.HubViewModel>()
                SearchProvidersPage(hub.webSearch, goBack, manual = hub.manualSearch)
            }
            detail == SettingsEntry.Mcp -> io.hydrion.hub.ui.settings.McpSettings(viewModel(), goBack)
            detail == SettingsEntry.Skills -> io.hydrion.hub.ui.settings.SkillSettings(viewModel(), goBack)
            detail == SettingsEntry.Environments -> io.hydrion.hub.ui.settings.ContainerSettings(viewModel(), goBack)
            detail == SettingsEntry.Memory -> io.hydrion.hub.ui.settings.MemorySettings(viewModel(), goBack)
            detail == SettingsEntry.Assistants -> io.hydrion.hub.ui.settings.AssistantSettings(viewModel(), goBack)
            detail == SettingsEntry.Backup -> io.hydrion.hub.ui.settings.BackupSettings(viewModel(), goBack)
            detail == SettingsEntry.Data -> io.hydrion.hub.ui.settings.StorageSettings(viewModel(), onBack = goBack)
            detail == SettingsEntry.Style -> MessageStyleSettings(onBack = goBack)
            else -> SettingsHome(
                search = search, onSearch = { search = it }, onBack = goBack,
                onOpen = { entry ->
                    when (entry) {
                        SettingsEntry.Language -> navigation.mode = Mode.Chat
                        SettingsEntry.Image -> navigation.mode = Mode.Image
                        else -> detail = entry
                    }
                }
            )
        }
    }
    if (detail == SettingsEntry.About) {
        AlertDialog(onDismissRequest = { detail = null },
            title = { Text("关于 HydrionHub") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("HydrionHub · 氢离子\n原生 Kotlin 与 Jetpack Compose 多模型客户端。")
                    OutlinedButton(onClick = { DictationRmsDiagnostic.armed = !DictationRmsDiagnostic.armed }) {
                            Text(stringResource(if (DictationRmsDiagnostic.armed) R.string.dictation_rms_disarm
                                else R.string.dictation_rms_arm))
                    }
                }
            },
            confirmButton = { TextButton(onClick = { detail = null }) { Text("完成") } })
    }
}

@Composable
internal fun Header(title: String, onBack: () -> Unit, action: @Composable () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Surface(modifier = Modifier.capsuleEdge(), shape = CircleShape, color = MaterialTheme.colorScheme.surfaceVariant) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.settings_back)) }
        }
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        action()
    }
}

@Composable
internal fun EditField(label: String, value: String, onValueChange: (String) -> Unit) {
    OutlinedTextField(value, onValueChange, Modifier.fillMaxWidth(), label = { Text(label) },
        singleLine = true, shape = RoundedCornerShape(14.dp))
}

@Composable
internal fun ConfirmRemoval(question: String, onDismiss: () -> Unit, onRemove: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text(question) },
        confirmButton = { TextButton(onClick = onRemove) { Text(stringResource(R.string.drawer_delete), color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.settings_cancel)) } })
}
