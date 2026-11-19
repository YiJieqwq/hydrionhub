package io.hydrion.hub.provider

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.hydrion.hub.model.Mode

@Composable
internal fun ProviderConfigTab(
    provider: Provider, apiKey: String, sample: Map<String, Any>, scroll: ScrollState, existing: Boolean,
    onUpdate: (Provider) -> Unit, onKey: (String) -> Unit, onModels: () -> Unit, onDelete: () -> Unit
) {
    var protocolDialog by rememberSaveable { mutableStateOf(false) }
    var reveal by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        GroupTitle("供应商管理")
        Surface(Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surfaceContainerLow, shape = RoundedCornerShape(16.dp)) {
            Column {
                ManageSwitch("启用供应商", Icons.Outlined.PowerSettingsNew, provider.enabled) { onUpdate(provider.copy(enabled = it)) }
                ManageLink("API 协议", Icons.Outlined.Cable, provider.protocol,
                    if (provider.id == DEEPSEEK_PROVIDER_ID) null else { { protocolDialog = true } })
                ManageLink("管理模型", Icons.Outlined.ViewInAr, provider.models.size.toString(), onModels)
            }
        }
        GroupTitle("API 配置")
        ConfigField("Name", "供应商名称", provider.name, { onUpdate(provider.copy(name = it)) })
        ConfigField("API Key", "API Key", apiKey, onKey, placeholder = "仅在本次运行中保留",
            keyboardType = KeyboardType.Password,
            transformation = if (reveal) VisualTransformation.None else PasswordVisualTransformation(),
            action = {
                IconButton(onClick = { reveal = !reveal }) {
                    Icon(if (reveal) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                        if (reveal) "隐藏 API Key" else "显示 API Key", Modifier.size(21.dp))
                }
            })
        ConfigField("Base URL", "Base URL", provider.baseUrl, { onUpdate(provider.copy(baseUrl = it)) },
            keyboardType = KeyboardType.Uri, placeholder = "https://")
        if (provider.id == DEEPSEEK_PROVIDER_ID) {
            val endpoint = remember(provider.baseUrl) { runCatching { provider.deepSeekEndpoint() } }
            Text(endpoint.getOrElse { it.message ?: "服务地址无效" }, style = MaterialTheme.typography.bodySmall,
                color = if (endpoint.isSuccess) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error)
        }
        if (provider.mode == Mode.Image) {
            TemplateField(provider.requestBody, sample, onChange = { onUpdate(provider.copy(requestBody = it)) })
        }
        if (existing) TextButton(onClick = onDelete, modifier = Modifier.align(Alignment.CenterHorizontally)) {
            Text("删除供应商", color = MaterialTheme.colorScheme.error)
        }
        Spacer(Modifier.height(8.dp))
    }

    if (protocolDialog) {
        var protocol by rememberSaveable { mutableStateOf(provider.protocol) }
        AlertDialog(onDismissRequest = { protocolDialog = false }, title = { Text("API 协议") },
            text = { ConfigField("协议类型", "协议类型", protocol, { protocol = it }) },
            dismissButton = { TextButton(onClick = { protocolDialog = false }) { Text("取消") } },
            confirmButton = { TextButton(enabled = protocol.isNotBlank(), onClick = {
                onUpdate(provider.copy(protocol = protocol.trim()))
                protocolDialog = false
            }) { Text("完成") } })
    }
}

@Composable
private fun ManageSwitch(label: String, icon: ImageVector, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().toggleable(checked, role = Role.Switch, onValueChange = onChange)
        .semantics { contentDescription = label }.heightIn(min = 52.dp).padding(horizontal = 14.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(icon, null, Modifier.size(20.dp), MaterialTheme.colorScheme.onSurfaceVariant)
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Switch(checked, onCheckedChange = null)
    }
}

@Composable
private fun ManageLink(label: String, icon: ImageVector, value: String, onClick: (() -> Unit)?) {
    val action = if (onClick == null) Modifier else Modifier.clickable(onClick = onClick)
    Row(Modifier.fillMaxWidth().then(action).heightIn(min = 50.dp).padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Icon(icon, null, Modifier.size(20.dp), MaterialTheme.colorScheme.onSurfaceVariant)
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Text(value, Modifier.weight(1f), maxLines = 2, textAlign = TextAlign.End, overflow = TextOverflow.Ellipsis,
            color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        if (onClick != null) Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null, Modifier.size(18.dp), MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun GroupTitle(text: String) {
    Text(text, Modifier.padding(top = 10.dp), style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun ConfigField(
    label: String, description: String, value: String, onChange: (String) -> Unit,
    keyboardType: KeyboardType = KeyboardType.Text, placeholder: String? = null,
    transformation: VisualTransformation = VisualTransformation.None,
    action: (@Composable () -> Unit)? = null
) {
    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
        TextField(value, onChange, Modifier.fillMaxWidth().semantics { contentDescription = description },
            singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
            placeholder = placeholder?.let { { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) } },
            trailingIcon = action, visualTransformation = transformation,
            shape = RoundedCornerShape(12.dp), colors = fieldColors())
    }
}

@Composable
private fun fieldColors() = TextFieldDefaults.colors(
    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent
)

@Composable
private fun TemplateField(value: String, sample: Map<String, Any>, onChange: (String) -> Unit) {
    var result by remember(value, sample) { mutableStateOf<Result<String>?>(null) }
    GroupTitle("自定义请求体")
    TextField(value, onChange, Modifier.fillMaxWidth().semantics { contentDescription = "JSON 模板" },
        minLines = 4, maxLines = 10, shape = RoundedCornerShape(12.dp), colors = fieldColors(),
        textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace))
    Text(sample.keys.joinToString("  ") { "{{$it}}" },
        color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
    OutlinedButton(onClick = { result = RequestTemplate.preview(value, sample) }) { Text("预览请求体") }
    result?.fold(onSuccess = { json ->
        Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
            Text(json, Modifier.fillMaxWidth().padding(12.dp),
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace))
        }
    }, onFailure = { error ->
        Text("请求体有误：${error.message.orEmpty()}", color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall)
    })
}
