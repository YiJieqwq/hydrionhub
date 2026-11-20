package io.hydrion.hub.provider

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
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
    val fastToggle = speed?.takeIf { it.choices.size == 2 && "Fast" in it.choices && "Standard" in it.choices }
    val accent = when (level?.effortKey()) {
        "max" -> Color(0xFF68A9FF)
        "ultra" -> Color(0xFFBA95FF)
        else -> colors.onSurface
    }
    val selectLabel = stringResource(R.string.chat_controls_select_model)
    val keepEditorFocus = Modifier.focusProperties { canFocus = false }

    Column(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 4.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
            if (fastToggle != null) {
                val fast = (values[fastToggle.key] ?: fastToggle.choices.first()) == "Fast"
                Box(keepEditorFocus.size(48.dp).testTag("chat-model-speed")
                    .toggleable(fast, role = Role.Switch) { onChoose(fastToggle, if (it) "Fast" else "Standard") },
                    contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.Bolt, stringResource(R.string.chat_controls_fast),
                        Modifier.size(18.dp), tint = if (fast) accent else colors.onSurfaceVariant)
                }
            } else Spacer(Modifier.width(48.dp))
            Column(keepEditorFocus.weight(1f).heightIn(min = 48.dp)
                .testTag("chat-model-select").clickable(role = Role.Button, onClickLabel = selectLabel, onClick = onModels),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                if (effort != null && level != null) {
                    Text(chatOptionLabel(effort, level), color = accent, style = MaterialTheme.typography.titleMedium,
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
            Box(keepEditorFocus.size(48.dp).testTag("chat-model-reset").clickable(role = Role.Button, onClick = onReset),
                contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.RestartAlt, stringResource(R.string.chat_controls_reset),
                    Modifier.size(18.dp), tint = colors.onSurfaceVariant)
            }
        }
        if (effort != null && level != null && effort.choices.size > 1) {
            EffortTrack(effort, level, accent) { onChoose(effort, it) }
        }
        for (cap in caps) {
            if (cap == effort || cap == fastToggle) continue
            val value = values[cap.key] ?: cap.choices.first()
            val tag = if (cap.key == "speed") "chat-model-speed" else "chat-model-option-${cap.key}"
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EffortTrack(cap: Capability, value: String, accent: Color, onChoose: (String) -> Unit) {
    val choices = remember(cap) {
        val order = effortLabels.keys.toList()
        if (cap.choices.all { it.effortKey() in effortLabels }) cap.choices.sortedBy { order.indexOf(it.effortKey()) }
        else cap.choices
    }
    val index = choices.indexOf(value).coerceAtLeast(0)
    val label = chatCapabilityLabel(cap)
    val level = chatOptionLabel(cap, choices[index])
    val rail = MaterialTheme.colorScheme.outlineVariant
    val tick = MaterialTheme.colorScheme.outline
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
            Canvas(Modifier.fillMaxWidth().height(8.dp)) {
                val rtl = layoutDirection == LayoutDirection.Rtl
                fun point(i: Int): Offset {
                    val x = size.width * i / choices.lastIndex
                    return Offset(if (rtl) size.width - x else x, center.y)
                }
                drawLine(rail, point(0), point(choices.lastIndex), 2.dp.toPx(), StrokeCap.Round)
                drawLine(accent, point(0), point(index), 2.dp.toPx(), StrokeCap.Round)
                for (i in choices.indices) drawCircle(if (i <= index) accent else tick, 2.dp.toPx(), point(i))
            }
        },
    )
}
