package io.hydrion.hub.provider

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.hydrion.hub.R
import java.util.Locale

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
    onPeakChanged: (Boolean) -> Unit = {},
) {
    val colors = MaterialTheme.colorScheme
    val caps = remember(model) { model.configureCapabilities() }
    val effort = caps.firstOrNull { it.key == "effort" }
    val level = effort?.let { values[it.key] ?: it.choices.first() }
    val speed = caps.firstOrNull { it.key == "speed" }
    val levels = remember(effort, model.profile.levels) {
        effort?.let { orderedEffortLevels(it.choices, model.profile.levels) }.orEmpty()
    }
    var preview by remember(model, levels, level) { mutableStateOf<String?>(null) }
    val shown = preview ?: level
    val dark = colors.surface.luminance() < .5f
    val palette = remember(levels, shown, dark) { effortPalette(levels, shown, dark) }
    SideEffect { onPeakChanged(palette.isPeak) }
    val accelerated = speeds.selected != null && speeds.selected == speeds.fast
    val titleTint by animateColorAsState(
        targetValue = if (effort == null) colors.onSurface else palette.ink,
        animationSpec = tween(200, easing = EffortTintEase), label = "effortTitle",
    )
    val selectLabel = stringResource(R.string.chat_controls_select_model)
    val keepEditorFocus = Modifier.focusProperties { canFocus = false }

    Column(Modifier.fillMaxWidth()) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val buttonWidth = minOf(48.dp, maxWidth / 3)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                val fastTint = when {
                    palette.isPeak && dark -> Color(0xFFC39BFF)
                    palette.isPeak -> Color(0xFF9B48EB)
                    dark -> Color(0xFF7AAAFF)
                    else -> Color(0xFF3988F6)
                }
                FastButton(speeds.fast, speeds.off, speeds.selected, buttonWidth, fastTint) {
                    speed?.let { cap -> onChoose(cap, it) }
                }
                Column(
                    keepEditorFocus.weight(1f).heightIn(min = 60.dp)
                        .clip(RoundedCornerShape(5.dp)).testTag("chat-model-select")
                        .clickable(role = Role.Button, onClickLabel = selectLabel, onClick = onModels)
                        .padding(start = 4.dp, end = 4.dp, top = 6.dp, bottom = 2.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(if (effort != null && shown != null) chatOptionLabel(effort, shown) else selectLabel,
                        Modifier.testTag("chat-model-effort-title"), color = titleTint,
                        style = MaterialTheme.typography.bodyLarge, fontSize = 16.sp, lineHeight = 22.sp,
                        letterSpacing = 0.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Row(verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(model.name, Modifier.weight(1f, fill = false).testTag("chat-model-name"),
                            color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium,
                            fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.sp,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null,
                            Modifier.size(14.dp).testTag("chat-model-name-chevron"), tint = colors.onSurfaceVariant)
                    }
                }
                val resetLabel = stringResource(R.string.chat_controls_reset)
                Box(keepEditorFocus.width(buttonWidth).height(48.dp).testTag("chat-model-reset")
                    .semantics { contentDescription = resetLabel }
                    .clickable(role = Role.Button, onClick = onReset), contentAlignment = Alignment.Center) {
                    EffortToolGlyph(resetGlyph, colors.onSurfaceVariant, Modifier.offset(x = 3.dp, y = (-3).dp))
                }
            }
        }
        if (effort != null && level != null) {
            EffortTrack(effort, levels, level, peakEffortIndex(levels), accelerated,
                onPreview = { preview = it }, onChoose = { onChoose(effort, it) })
            Spacer(Modifier.height(2.dp))
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

private val EffortTintEase = CubicBezierEasing(.25f, .1f, .25f, 1f)
private val nonStrengths = setOf("off", "none", "disabled", "auto", "default")

internal fun orderedEffortLevels(choices: List<String>, declared: List<String>): List<String> {
    val knownOrder = effortLabels.keys.toList()
    if (choices.all { it.effortKey() in effortLabels }) return choices.sortedBy { knownOrder.indexOf(it.effortKey()) }
    // configureCapabilities puts the default first. Recover the configured order of custom levels.
    return (choices.filter { it.effortKey() in nonStrengths } + declared.filter { it in choices } + choices).distinct()
}

@Composable
private fun FastButton(tier: String?, off: String?, selected: String?, width: Dp, activeTint: Color,
    onChange: (String) -> Unit) {
    val enabled = tier != null && off != null
    val active = tier != null && selected == tier
    val name = stringResource(R.string.chat_controls_fast)
    val description = when {
        enabled -> name
        active -> stringResource(R.string.chat_controls_fixed, name)
        else -> stringResource(R.string.chat_controls_not_connected, name)
    }
    val colors = MaterialTheme.colorScheme
    val tint = when {
        active -> activeTint.copy(alpha = if (enabled) 1f else .65f)
        !enabled -> colors.onSurface.copy(alpha = .32f)
        else -> colors.onSurfaceVariant
    }
    Box(Modifier.width(width).height(48.dp).focusProperties { canFocus = false }
        .testTag("chat-model-fast")
        .semantics { contentDescription = description }
        .toggleable(active, enabled = enabled, role = Role.Switch) { on ->
            (if (on) tier else off)?.let(onChange)
        }, contentAlignment = Alignment.Center) {
        EffortToolGlyph(boltGlyph, tint, Modifier.offset(x = (-3).dp, y = (-3).dp), filled = active)
    }
}

// Native effort panel adapted from github.com/WanX609/dsh-codex-model-effort-selector
// at 0d7546c657aab043cf278e4b1e3a7ed7cf13ef54. See assets/dsh-codex-model-effort-selector-LICENSE.txt.
private val boltGlyph by lazy {
    PathParser().parsePathString(
        "M14.4 2Q16 .2 15.8 2.5L15.1 8.5H20.5Q22 8.5 21 9.8L10.7 21.7Q9 23.4 9.2 21.2L10 14.2H4.2Q2.6 14.2 3.6 12.9L14.4 2Z"
    ).toPath()
}
private val resetGlyph by lazy { PathParser().parsePathString("M3 11a9 9 0 1 1 2.6 7M3 4v7h7").toPath() }

@Composable
private fun EffortToolGlyph(path: Path, tint: Color, modifier: Modifier, filled: Boolean = false) {
    Canvas(modifier.size(16.dp)) {
        scale(size.width / 24f, size.height / 24f, Offset.Zero) {
            if (filled) drawPath(path, tint)
            drawPath(path, tint, style = Stroke(if (filled) 0.7f else 1.7f, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    }
}
