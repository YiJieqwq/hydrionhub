package io.hydrion.hub.provider

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier

private val levelTitles = linkedMapOf(
    "Minimal" to "最低", "Low" to "低", "Medium" to "中", "High" to "高",
    "Extra High" to "极高", "Max" to "最高"
)

@Composable
internal fun ModelReasoningTab(profile: ModelProfile, onUpdate: (ModelProfile) -> Unit) {
    if (profile.type != null && profile.type != ModelType.Chat) {
        Text("推理设置适用于对话模型。", style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    if ("reasoning" !in profile.abilities) {
        ModelGroup("推理能力") {
            ModelToggle("启用推理", false) { enabled ->
                if (enabled) onUpdate(profile.copy(abilities = profile.abilities + "reasoning"))
            }
        }
        return
    }

    ModelGroup("推理方言") {
        EditableReasoningChoice("方言", profile.dialect,
            listOf("" to "自动", "reasoning_effort" to "OpenAI · reasoning_effort")) {
            onUpdate(profile.copy(dialect = it))
        }
    }
    val levels = levelTitles + profile.levels.filterNot { it in levelTitles }.associateWith { it }
    ModelGroup("支持的档位") {
        ModelChips(levels.toList(), profile.levels.toSet()) { choice ->
            val next = if (choice in profile.levels) profile.levels - choice else profile.levels + choice
            val removingDefault = choice == profile.defaultLevel && choice !in next
            onUpdate(profile.copy(levels = next, defaultLevel = if (removingDefault) "" else profile.defaultLevel))
        }
        ModelToggle("允许关闭推理", profile.allowDisabling) { enabled ->
            val default = if (!enabled && profile.defaultLevel == "Off") "" else profile.defaultLevel
            onUpdate(profile.copy(allowDisabling = enabled, defaultLevel = default))
        }
        val defaults = buildList {
            add("" to "自动")
            profile.levels.filterNot { it in setOf("Auto", "Off", "") }.forEach { add(it to levels.getValue(it)) }
            if (profile.allowDisabling) add("Off" to "关闭")
        }
        val selected = profile.defaultLevel.takeUnless { it == "Auto" }.orEmpty()
        ModelChoice("默认档位", selected, withCurrent(defaults, selected)) {
            onUpdate(profile.copy(defaultLevel = it))
        }
    }
    ModelGroup("策略") {
        EditableReasoningChoice("采样", profile.sampling, listOf(
            "" to "自动", "never/strip" to "从不（移除采样参数）", "keep" to "保留采样参数"
        )) { onUpdate(profile.copy(sampling = it)) }
        EditableReasoningChoice("推理回放", profile.reasoningReplay, listOf(
            "" to "自动", "all" to "全部", "last" to "仅上一轮", "none" to "关闭"
        )) { onUpdate(profile.copy(reasoningReplay = it)) }
        EditableReasoningChoice("回放字段", profile.replayField, listOf(
            "" to "自动", "reasoning_content" to "reasoning_content"
        )) { onUpdate(profile.copy(replayField = it)) }
    }
}

private fun withCurrent(options: List<Pair<String, String>>, value: String): List<Pair<String, String>> =
    if (options.any { it.first == value }) options else options + (value to value)

@Composable
private fun EditableReasoningChoice(
    label: String, value: String, presets: List<Pair<String, String>>, onChange: (String) -> Unit
) {
    var entering by rememberSaveable { mutableStateOf(false) }
    // An empty DB value is the inherited option. The input action is never written into the profile.
    val customAction = "\u0000"
    ModelChoice(label, value, withCurrent(presets, value) + (customAction to "自定义…")) { choice ->
        if (choice == customAction) entering = true else onChange(choice)
    }
    if (entering) {
        var draft by rememberSaveable { mutableStateOf(value) }
        AlertDialog(onDismissRequest = { entering = false }, title = { Text("自定义$label") }, text = {
            Column(Modifier.fillMaxWidth()) { ModelField(label, draft, { draft = it }) }
        }, dismissButton = { TextButton(onClick = { entering = false }) { Text("取消") } },
            confirmButton = { TextButton(enabled = draft.isNotBlank(), onClick = {
                onChange(draft.trim())
                entering = false
            }) { Text("完成") } })
    }
}
