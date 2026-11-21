package io.hydrion.hub.provider

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.constrainWidth

internal val PeakEffortLabelColor = Color(0xFF924FF7)

internal data class ChatTriggerLabel(
    val name: String,
    val effort: String? = null,
    val peak: Boolean = false,
    val effortLabels: List<String> = listOfNotNull(effort),
) {
    val text = if (effort == null) name else "$name $effort"

    fun width(measurer: TextMeasurer, style: TextStyle, spaceWidth: Int): Int {
        fun widthOf(text: String) = measurer.measure(text, style, maxLines = 1, softWrap = false).size.width
        return widthOf(name) + (effort?.let { spaceWidth + effortWidth(::widthOf) } ?: 0)
    }

    fun effortWidth(measure: (String) -> Int): Int = effortLabels.maxOfOrNull(measure) ?: 0
}

internal data class SelectedChatEffort(val capability: Capability, val value: String, val peak: Boolean)

internal fun ModelCatalog.selectedChatEffort(entry: Pair<ProviderModel, Provider>?): SelectedChatEffort? {
    val (model, provider) = entry ?: return null
    val cap = model.configureCapabilities().firstOrNull { it.key == "effort" } ?: return null
    val value = parameters(model, provider)[cap.key] ?: return null
    val levels = orderedEffortLevels(cap.choices, model.profile.levels)
    return SelectedChatEffort(cap, value, levels.getOrNull(peakEffortIndex(levels)) == value)
}

@Composable
internal fun ModelCatalog.chatTriggerLabel(entry: Pair<ProviderModel, Provider>?, name: String): ChatTriggerLabel {
    val chosen = remember(this, entry) { selectedChatEffort(entry) }
    if (chosen == null || entry == null) return ChatTriggerLabel(name)
    val currentLabel = chatOptionLabel(chosen.capability, chosen.value)
    val labels = if (entry.first.hasAdjustableEffort(chosen.capability)) {
        chosen.capability.choices.map { chatOptionLabel(chosen.capability, it) }
    } else listOf(currentLabel)
    return ChatTriggerLabel(name, currentLabel, chosen.peak, labels)
}

/** Keep the configured effort legible alongside an ellipsized model name, in either layout direction. */
@Composable
internal fun ChatTriggerText(label: ChatTriggerLabel, dark: Boolean, style: TextStyle, gapPx: Int, modifier: Modifier) {
    val colors = MaterialTheme.colorScheme
    val measurer = rememberTextMeasurer()
    val widestEffort = remember(measurer, style, label.effortLabels) {
        label.effortWidth { measurer.measure(it, style, maxLines = 1, softWrap = false).size.width }
    }
    Layout(modifier = modifier, content = {
        Text(label.name, style = style, color = if (dark) Color.White else colors.onSurface,
            textAlign = TextAlign.End, maxLines = 1, overflow = TextOverflow.Ellipsis)
        label.effort?.let { effort ->
            val ink = if (label.peak) PeakEffortLabelColor else if (dark) Color(0xFFAFAFAF) else colors.onSurfaceVariant
            // Keep unused width beyond the suffix, including Latin labels in an RTL toolbar.
            val nearName = if (LocalLayoutDirection.current == LayoutDirection.Ltr) TextAlign.Left else TextAlign.Right
            Text(effort, style = style, color = ink, textAlign = nearName,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }) { children, limits ->
        val name = children.first()
        // Under severe truncation even a user-defined effort may be long. Keep room for both labels.
        val nameReserve = minOf(name.maxIntrinsicWidth(Constraints.Infinity), limits.maxWidth / 2)
        val gap = if (children.size > 1) gapPx.coerceAtMost(limits.maxWidth - nameReserve) else 0
        val effortWidth = minOf(widestEffort, limits.maxWidth - nameReserve - gap)
        val effort = children.getOrNull(1)?.measure(limits.copy(minWidth = effortWidth, maxWidth = effortWidth))
        val used = (effort?.width ?: 0) + gap
        val nameBox = name.measure(limits.copy(minWidth = 0, maxWidth = limits.maxWidth - used))
        val width = limits.constrainWidth(nameBox.width + used)
        val height = limits.constrainHeight(maxOf(nameBox.height, effort?.height ?: 0))
        layout(width, height) {
            val end = width - (effort?.width ?: 0)
            nameBox.placeRelative(end - gap - nameBox.width, (height - nameBox.height) / 2)
            effort?.placeRelative(end, (height - effort.height) / 2)
        }
    }
}
