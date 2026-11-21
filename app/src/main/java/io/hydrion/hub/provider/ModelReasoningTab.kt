package io.hydrion.hub.provider

import androidx.compose.ui.res.stringResource
import io.hydrion.hub.R

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier

@Composable
internal fun ModelReasoningTab(profile: ModelProfile, onUpdate: (ModelProfile) -> Unit) {
    if (profile.type != null && profile.type != ModelType.Chat) {
        Text(stringResource(R.string.model_for_chat), style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    if ("reasoning" !in profile.abilities) {
        ModelGroup(stringResource(R.string.model_reasoning_ability)) {
            ModelToggle(stringResource(R.string.model_enable_reasoning), false) { enabled ->
                if (enabled) onUpdate(profile.copy(abilities = profile.abilities + "reasoning"))
            }
        }
        return
    }

    ModelGroup(stringResource(R.string.model_dialect_section)) {
        EditableReasoningChoice(stringResource(R.string.model_dialect), profile.dialect,
            listOf("" to stringResource(R.string.model_auto), "reasoning_effort" to "OpenAI · reasoning_effort")) {
            onUpdate(profile.copy(dialect = it))
        }
    }
    val levelTitles = linkedMapOf(
        "Minimal" to stringResource(R.string.model_minimal), "Low" to stringResource(R.string.model_low), "Medium" to stringResource(R.string.model_medium), "High" to stringResource(R.string.model_high),
        "Extra High" to stringResource(R.string.model_xhigh), "Max" to stringResource(R.string.model_max)
    )
    val levels = levelTitles + profile.levels.filterNot { it in levelTitles }.associateWith { it }
    ModelGroup(stringResource(R.string.model_levels)) {
        ModelChips(levels.toList(), profile.levels.toSet()) { choice ->
            val next = if (choice in profile.levels) profile.levels - choice else profile.levels + choice
            val removingDefault = choice == profile.defaultLevel && choice !in next
            onUpdate(profile.copy(levels = next, defaultLevel = if (removingDefault) "" else profile.defaultLevel))
        }
        ModelToggle(stringResource(R.string.model_can_disable), profile.allowDisabling) { enabled ->
            val default = if (!enabled && profile.defaultLevel == "Off") "" else profile.defaultLevel
            onUpdate(profile.copy(allowDisabling = enabled, defaultLevel = default))
        }
        val defaults = buildList {
            add("" to stringResource(R.string.model_auto))
            profile.levels.filterNot { it in setOf("Auto", "Off", "") }.forEach { add(it to levels.getValue(it)) }
            if (profile.allowDisabling) add("Off" to stringResource(R.string.model_off))
        }
        val selected = profile.defaultLevel.takeUnless { it == "Auto" }.orEmpty()
        ModelChoice(stringResource(R.string.model_default_level), selected, withCurrent(defaults, selected)) {
            onUpdate(profile.copy(defaultLevel = it))
        }
    }
    ModelGroup(stringResource(R.string.model_policy)) {
        EditableReasoningChoice(stringResource(R.string.model_sampling), profile.sampling, listOf(
            "" to stringResource(R.string.model_auto), "never/strip" to stringResource(R.string.model_strip_sampling), "keep" to stringResource(R.string.model_keep_sampling)
        )) { onUpdate(profile.copy(sampling = it)) }
        EditableReasoningChoice(stringResource(R.string.model_replay), profile.reasoningReplay, listOf(
            "" to stringResource(R.string.model_auto), "all" to stringResource(R.string.model_all), "last" to stringResource(R.string.model_last_turn), "none" to stringResource(R.string.model_off)
        )) { onUpdate(profile.copy(reasoningReplay = it)) }
        EditableReasoningChoice(stringResource(R.string.model_replay_field), profile.replayField, listOf(
            "" to stringResource(R.string.model_auto), "reasoning_content" to "reasoning_content"
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
    ModelChoice(label, value, withCurrent(presets, value) + (customAction to stringResource(R.string.model_custom_entry))) { choice ->
        if (choice == customAction) entering = true else onChange(choice)
    }
    if (entering) {
        var draft by rememberSaveable { mutableStateOf(value) }
        AlertDialog(onDismissRequest = { entering = false }, title = { Text(stringResource(R.string.model_custom_named, label)) }, text = {
            Column(Modifier.fillMaxWidth()) { ModelField(label, draft, { draft = it }) }
        }, dismissButton = { TextButton(onClick = { entering = false }) { Text(stringResource(R.string.settings_cancel)) } },
            confirmButton = { TextButton(enabled = draft.isNotBlank(), onClick = {
                onChange(draft.trim())
                entering = false
            }) { Text(stringResource(R.string.model_done)) } })
    }
}
