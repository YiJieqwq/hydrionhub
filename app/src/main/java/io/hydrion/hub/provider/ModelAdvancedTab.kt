package io.hydrion.hub.provider

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
    imageModels: List<ProviderModel>,
    onProfile: (ModelProfile) -> Unit,
    onBudget: (String) -> Unit,
    onImageBudget: (String) -> Unit,
    onHeaders: (List<HeaderDraft>) -> Unit
) {
    var editingBudget by rememberSaveable { mutableStateOf(false) }
    var editingBody by rememberSaveable { mutableStateOf(false) }
    val overrideBudget = imageBudget.isNotBlank() || budget.isNotBlank()
    ModelGroup("") {
        AdvancedAction("图片与请求预算", if (overrideBudget) "自定义" else "继承") { editingBudget = true }
    }
    ModelGroup("请求配置") {
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
            Text("添加请求头")
        }
        if (profile.requestBody.isEmpty()) {
            TextButton(onClick = { editingBody = true }) {
                Icon(Icons.Outlined.Add, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("添加请求体")
            }
        } else {
            AdvancedAction("自定义请求体", "编辑 JSON") { editingBody = true }
        }
    }
    ModelGroup("图片输入") {
        ModelToggle("远程图片链接", profile.imageLinks) { onProfile(profile.copy(imageLinks = it)) }
    }
    ModelGroup("内置工具") {
        ModelToggle("代码解释器", profile.codeInterpreter) { onProfile(profile.copy(codeInterpreter = it)) }
        ModelToggle("图像生成", profile.builtinImage) { onProfile(profile.copy(builtinImage = it)) }
        val options = buildList {
            add("" to "自动")
            if (profile.imageModelId.isNotEmpty() && imageModels.none { it.id == profile.imageModelId }) {
                add(profile.imageModelId to "已不可用")
            }
            addAll(imageModels.map { it.id to it.name })
        }
        ModelChoice("图像模型", profile.imageModelId, options) { onProfile(profile.copy(imageModelId = it)) }
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
            Text("请求头 $index", Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
            IconButton(remove) { Icon(Icons.Outlined.Close, "移除请求头 $index", Modifier.size(18.dp)) }
        }
        ModelField("请求头名称", header.key, { update(header.copy(key = it)) })
        CompositionLocalProvider(LocalSaveableStateRegistry provides null) {
            OutlinedTextField(header.value, { update(header.copy(value = it)) }, singleLine = true,
                modifier = Modifier.fillMaxWidth(), label = { Text("请求头值") }, shape = RoundedCornerShape(12.dp),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                visualTransformation = if (revealed) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    IconButton(onClick = { revealed = !revealed }) {
                        Icon(if (revealed) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                            if (revealed) "隐藏请求头值" else "显示请求头值", Modifier.size(19.dp))
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
    AlertDialog(onDismissRequest = onDismiss, title = { Text("图片与请求预算") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            ModelField("每次发送的图片上限（张）", imageLimit, { imageLimit = it }, keyboard = KeyboardType.Number)
            ModelField("每次发送的请求上限（次）", requestLimit, { requestLimit = it }, keyboard = KeyboardType.Number)
            if (!valid) Text("预算必须是非负整数。", color = MaterialTheme.colorScheme.error)
            else Text("留空继承，填 0 将禁止该项。", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = { onApply("", "") }) { Text("恢复继承") }
        }
    }, dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
        confirmButton = { TextButton(enabled = valid, onClick = {
            onApply(imageLimit.trim(), requestLimit.trim())
        }) { Text("完成") } })
}

@Composable
private fun BodyDialog(initial: String, onDismiss: () -> Unit, onApply: (String) -> Unit) {
    var json by rememberSaveable { mutableStateOf(initial.ifBlank { "{}" }) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("自定义请求体") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(json, { json = it; error = null }, modifier = Modifier.fillMaxWidth().semantics {
                contentDescription = "JSON 请求体"
            }, minLines = 4, maxLines = 8, label = { Text("JSON 对象") }, shape = RoundedCornerShape(12.dp))
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            if (initial.isNotEmpty()) TextButton(onClick = { onApply("") }) { Text("移除请求体") }
        }
    }, dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
        confirmButton = { TextButton(onClick = {
            val raw = json.trim()
            error = if (raw.isEmpty()) null else RequestTemplate.preview(raw, null).exceptionOrNull()?.let {
                "请求体有误：${it.message.orEmpty()}"
            }
            if (error == null) onApply(raw)
        }) { Text("完成") } })
}
