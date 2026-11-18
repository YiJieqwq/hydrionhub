package io.hydrion.hub.provider

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.unit.dp

private val modelTypes = listOf(ModelType.Chat to "对话", ModelType.Embedding to "嵌入", ModelType.Image to "图像")
private val inputs = listOf("text" to "文本", "image" to "图片", "audio" to "音频", "video" to "视频", "pdf" to "PDF")
private val outputs = inputs.take(3)
private val abilities = listOf("tools" to "工具调用", "reasoning" to "推理", "structured" to "结构化输出")

private fun Set<String>.toggled(key: String) = if (key in this) this - key else this + key

@Composable
internal fun ModelBasicTab(draft: ModelEditDraft) {
    var iconPicker by remember { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    val profile = draft.profile
    ModelGroup("") {
        Row(Modifier.fillMaxWidth().clickable { iconPicker = true }.heightIn(min = 48.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                ProfileIcon(profile.icon, draft.type, Modifier.padding(9.dp).size(22.dp))
            }
            Text("模型图标", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null, Modifier.size(18.dp))
        }
    }
    ModelField("模型 ID", profile.externalId, { draft.profile = profile.copy(externalId = it) },
        trailingIcon = { IconButton(enabled = profile.externalId.isNotBlank(), onClick = {
            clipboard.setText(AnnotatedString(profile.externalId))
        }) { Icon(Icons.Outlined.ContentCopy, "复制模型 ID", Modifier.size(19.dp)) } })
    ModelField("模型名称", draft.name, { draft.name = it })
    ModelGroup("模型类型") {
        Row(Modifier.fillMaxWidth().selectableGroup(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            modelTypes.forEach { (type, label) ->
                val active = type == draft.type
                Surface(Modifier.weight(1f), shape = RoundedCornerShape(10.dp),
                    color = if (active) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer) {
                    Box(Modifier.selectable(active, role = Role.RadioButton, onClick = { draft.changeType(type) })
                        .heightIn(min = 39.dp), contentAlignment = Alignment.Center) {
                        Text(label, color = if (active) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface)
                    }
                }
            }
        }
    }
    ModelGroup("输入") {
        ModelChips(inputs, profile.input) { draft.profile = profile.copy(input = profile.input.toggled(it)) }
    }
    ModelGroup("输出") {
        ModelChips(outputs, profile.output) { draft.profile = profile.copy(output = profile.output.toggled(it)) }
    }
    ModelGroup("能力") {
        ModelChips(abilities, profile.abilities) { key ->
            val selected = profile.abilities.toggled(key)
            val levels = if (key == "reasoning" && key in selected && profile.levels.isEmpty()) listOf("Low", "Medium", "High") else profile.levels
            draft.profile = profile.copy(abilities = selected, levels = levels)
        }
    }
    if (draft.type != ModelType.Embedding) ModelGroup("") {
        ModelToggle(if (draft.currentDefault) "当前默认模型" else "设为该模式的默认模型", draft.makeDefault,
            enabled = !draft.currentDefault) { draft.makeDefault = it }
    }
    if (iconPicker) {
        AlertDialog(onDismissRequest = { iconPicker = false }, title = { Text("选择模型图标") },
            text = { Column {
                (listOf("" to "自动") + profileIcons).forEach { (key, title) ->
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
            } }, confirmButton = { TextButton(onClick = { iconPicker = false }) { Text("取消") } })
    }
}
