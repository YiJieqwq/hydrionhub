package io.hydrion.hub.provider

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.hydrion.hub.R
import java.util.Locale
import kotlin.math.roundToInt

private val effortLabels = linkedMapOf(
    "off" to R.string.chat_controls_off, "auto" to R.string.chat_controls_auto,
    "minimal" to R.string.chat_controls_minimal, "low" to R.string.chat_controls_low,
    "medium" to R.string.chat_controls_medium, "high" to R.string.chat_controls_high,
    "extra high" to R.string.chat_controls_extra_high, "max" to R.string.chat_controls_max,
    "ultra" to R.string.chat_controls_ultra,
)

private fun String.effortKey() = when (val key = lowercase(Locale.ROOT)) {
    "xhigh" -> "extra high"
    else -> key
}

@Composable
internal fun chatCapabilityLabel(cap: Capability): String = when {
    cap.key == "effort" && cap.label == "Effort" -> stringResource(R.string.chat_controls_effort)
    cap.key == "speed" && cap.label == "Speed" -> stringResource(R.string.chat_controls_speed)
    else -> cap.label
}

@Composable
internal fun chatOptionLabel(cap: Capability, value: String): String {
    val resource = when (cap.key) {
        "effort" -> effortLabels[value.effortKey()]
        "speed" -> when (value) {
            "Fast" -> R.string.chat_controls_fast
            "Standard" -> R.string.chat_controls_standard
            else -> null
        }
        else -> null
    }
    return resource?.let { stringResource(it) } ?: value
}

@Composable
internal fun ChatModelControls(
    model: ProviderModel,
    values: Map<String, String>,
    speeds: ChatSpeedControls,
    onModels: () -> Unit,
    onChoose: (Capability, String) -> Unit,
    onOptions: (Capability) -> Unit,
    onReset: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val caps = remember(model) { model.configureCapabilities() }
    val effort = caps.firstOrNull { it.key == "effort" }
    val level = effort?.let { values[it.key] ?: it.choices.first() }
    val speed = caps.firstOrNull { it.key == "speed" }
    val levels = remember(effort, model.profile.levels) {
        effort?.let { orderedEffortLevels(it.choices, model.profile.levels) }.orEmpty()
    }
    val highest = levels.lastOrNull { it.effortKey() !in nonStrengths }
    val peak = level != null && level == highest
    val accelerated = speeds.selected != null && (speeds.selected == speeds.fast || speeds.selected == speeds.ultra)
    val accent = if (peak) Color(0xFFB88BFF) else Color(0xFF619EFF)
    val selectLabel = stringResource(R.string.chat_controls_select_model)
    val keepEditorFocus = Modifier.focusProperties { canFocus = false }

    Column(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 4.dp)) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val buttonWidth = minOf(48.dp, maxWidth / 4)
            val middleWidth = minOf(maxWidth * .44f, maxWidth - buttonWidth * 3)
            val leftWidth = maxOf(buttonWidth * 2, (maxWidth - middleWidth) / 2)
            Row(verticalAlignment = Alignment.Top) {
                Row(Modifier.width(leftWidth)) {
                    SpeedButton(speeds.fast, speeds.off, speeds.selected, buttonWidth, ultra = false) {
                        speed?.let { cap -> onChoose(cap, it) }
                    }
                    SpeedButton(speeds.ultra, speeds.off, speeds.selected, buttonWidth, ultra = true) {
                        speed?.let { cap -> onChoose(cap, it) }
                    }
                }
                Column(keepEditorFocus.width(middleWidth).heightIn(min = 54.dp)
                    .clip(RoundedCornerShape(10.dp)).testTag("chat-model-select")
                    .clickable(role = Role.Button, onClickLabel = selectLabel, onClick = onModels)
                    .padding(horizontal = 3.dp, vertical = 3.dp),
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    if (effort != null && level != null) {
                        Text(chatOptionLabel(effort, level), style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(model.name, Modifier.weight(1f, fill = false),
                            color = if (effort == null) colors.onSurface else colors.onSurfaceVariant,
                            style = if (effort == null) MaterialTheme.typography.titleSmall else MaterialTheme.typography.labelMedium,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Icon(Icons.Outlined.KeyboardArrowDown, null, Modifier.size(14.dp), tint = colors.onSurfaceVariant)
                    }
                }
                Box(Modifier.weight(1f), contentAlignment = Alignment.TopEnd) {
                    Box(keepEditorFocus.width(buttonWidth).height(48.dp).testTag("chat-model-reset")
                        .clickable(role = Role.Button, onClick = onReset), contentAlignment = Alignment.Center) {
                        Icon(Icons.Outlined.RestartAlt, stringResource(R.string.chat_controls_reset),
                            Modifier.size(18.dp), tint = colors.onSurfaceVariant)
                    }
                }
            }
        }
        if (effort != null && level != null) {
            EffortTrack(effort, levels, level, accent, peak, accelerated) { onChoose(effort, it) }
        }
        for (cap in caps) {
            if (cap == effort || cap == speed) continue
            val value = values[cap.key] ?: cap.choices.first()
            val tag = "chat-model-option-${cap.key}"
            Row(keepEditorFocus.fillMaxWidth().heightIn(min = 48.dp).testTag(tag)
                .clickable(role = Role.Button) { onOptions(cap) }.padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(chatCapabilityLabel(cap), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                Text(chatOptionLabel(cap, value), Modifier.weight(1f, fill = false), maxLines = 1,
                    overflow = TextOverflow.Ellipsis, color = colors.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium)
                Icon(Icons.Outlined.KeyboardArrowDown, null, Modifier.size(16.dp), tint = colors.onSurfaceVariant)
            }
        }
    }
}

private val nonStrengths = setOf("off", "none", "disabled", "auto", "default")

internal fun orderedEffortLevels(choices: List<String>, declared: List<String>): List<String> {
    val knownOrder = effortLabels.keys.toList()
    if (choices.all { it.effortKey() in effortLabels }) return choices.sortedBy { knownOrder.indexOf(it.effortKey()) }
    // configureCapabilities puts the default first. Recover the configured order of custom levels.
    return (choices.filter { it.effortKey() in nonStrengths } + declared.filter { it in choices } + choices).distinct()
}

@Composable
private fun SpeedButton(tier: String?, off: String?, selected: String?, width: Dp,
    ultra: Boolean, onChange: (String) -> Unit) {
    val enabled = tier != null && off != null
    val active = tier != null && selected == tier
    val name = stringResource(if (ultra) R.string.chat_controls_ultrafast else R.string.chat_controls_fast)
    val description = when {
        enabled -> name
        active -> stringResource(R.string.chat_controls_fixed, name)
        else -> stringResource(R.string.chat_controls_not_connected, name)
    }
    val colors = MaterialTheme.colorScheme
    val tint = when {
        active -> Color(0xFF8EB9FF).copy(alpha = if (enabled) 1f else .65f)
        !enabled -> colors.onSurface.copy(alpha = .32f)
        else -> colors.onSurfaceVariant
    }
    Box(Modifier.width(width).height(48.dp).focusProperties { canFocus = false }
        .testTag(if (ultra) "chat-model-ultrafast" else "chat-model-fast")
        .semantics { contentDescription = description }
        .toggleable(active, enabled = enabled, role = Role.Switch) { on ->
            (if (on) tier else off)?.let(onChange)
        }, contentAlignment = Alignment.Center) {
        if (ultra) {
            Icon(Icons.Filled.Bolt, null, Modifier.offset(x = (-3).dp, y = (-1).dp).size(18.dp), tint = tint)
            Icon(Icons.Filled.Bolt, null, Modifier.offset(x = 3.dp, y = 1.dp).size(18.dp), tint = tint)
        } else Icon(Icons.Filled.Bolt, null, Modifier.size(19.dp), tint = tint)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EffortTrack(cap: Capability, choices: List<String>, value: String, accent: Color,
    peak: Boolean, accelerated: Boolean, onChoose: (String) -> Unit) {
    val index = choices.indexOf(value).coerceAtLeast(0)
    val label = chatCapabilityLabel(cap)
    val level = chatOptionLabel(cap, choices[index])
    if (choices.size == 1) {
        val fixed = stringResource(R.string.chat_controls_fixed, level)
        Box(Modifier.fillMaxWidth().padding(horizontal = 12.dp).heightIn(min = 48.dp)
            .testTag("chat-model-effort").semantics { contentDescription = label; stateDescription = fixed },
            contentAlignment = Alignment.Center) {
            EffortRail(0, 0, accent, peak, accelerated)
        }
        return
    }
    Slider(
        value = index.toFloat(),
        onValueChange = { number ->
            val next = choices[number.roundToInt().coerceIn(choices.indices)]
            if (next != value) onChoose(next)
        },
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp).focusProperties { canFocus = false }
            .testTag("chat-model-effort").semantics { contentDescription = label; stateDescription = level },
        valueRange = 0f..choices.lastIndex.toFloat(), steps = choices.size - 2,
        interactionSource = remember { MutableInteractionSource() },
        thumb = { Box(Modifier.size(12.dp).background(accent, CircleShape)) },
        track = {
            EffortRail(index, choices.lastIndex, accent, peak, accelerated)
        },
    )
}
