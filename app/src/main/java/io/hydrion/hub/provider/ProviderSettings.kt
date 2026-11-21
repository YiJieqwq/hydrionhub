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
import io.hydrion.hub.ui.theme.capsuleEdge
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import io.hydrion.hub.model.Mode
import java.util.UUID

private fun category(mode: Mode) = if (mode == Mode.Chat) "聊天模型供应商" else "图像生成模型供应商"

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
            currentMode != null -> Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding().padding(horizontal = 16.dp)) {
                Header(category(currentMode), goBack) {
                    IconButton(onClick = {
                        val blank = Provider(UUID.randomUUID().toString(), currentMode, "",
                            if (currentMode == Mode.Chat) "OpenAI Compatible" else "OpenAI Images", "", emptyList())
                        navigation.open(blank, store)
                    }) { Icon(Icons.Default.Add, "添加供应商") }
                }
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(top = 14.dp, bottom = 24.dp)) {
                    val providers = catalog.providers.filter { it.mode == currentMode }
                    if (providers.isEmpty()) item {
                        Text("还没有供应商，点击右上角添加。", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    items(providers, key = { it.id }) { provider ->
                        val defaultIds = Mode.entries.mapNotNull { catalog.defaultModel(it)?.id }
                        val defaults = provider.models.filter { it.id in defaultIds }.joinToString { it.name }
                        val connection = if (provider.id == DEEPSEEK_PROVIDER_ID) "在线" else "本地模拟"
                        val subtitle = listOfNotNull(if (provider.enabled) null else "已停用", connection, provider.protocol,
                            "${provider.models.size} 个模型", defaults.takeIf { it.isNotEmpty() }?.let { "默认 · $it" })
                        LinkRow(provider.name, subtitle.joinToString("  ·  ")) { navigation.open(provider, store) }
                    }
                    if (currentMode == Mode.Chat && providers.none { it.id == DEEPSEEK_PROVIDER_ID }) {
                        item { LinkRow("添加 DeepSeek", deepSeekProvider.protocol) { navigation.open(deepSeekProvider, store) } }
                    }
                }
            }
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
    if (detail == SettingsEntry.Data || detail == SettingsEntry.About) {
        val about = detail == SettingsEntry.About
        AlertDialog(onDismissRequest = { detail = null },
            title = { Text(if (about) "关于 HydrionHub" else "数据管理") },
            text = { Text(if (about) "HydrionHub · 氢离子\n原生 Kotlin 与 Jetpack Compose 多模型客户端。"
                else "会话历史、供应商配置和消息样式保存在本机。可在侧栏管理会话，在供应商页面管理模型。") },
            confirmButton = { TextButton(onClick = { detail = null }) { Text("完成") } })
    }
}

@Composable
internal fun Header(title: String, onBack: () -> Unit, action: @Composable () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Surface(modifier = Modifier.capsuleEdge(), shape = CircleShape, color = MaterialTheme.colorScheme.surfaceVariant) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") }
        }
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        action()
    }
}

@Composable
private fun LinkRow(title: String, subtitle: String? = null, onClick: () -> Unit) {
    Surface(shape = RoundedCornerShape(17.dp), color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.bodyLarge)
                if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
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
        confirmButton = { TextButton(onClick = onRemove) { Text("删除", color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}
