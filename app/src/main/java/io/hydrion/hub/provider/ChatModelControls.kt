package io.hydrion.hub.provider

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
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
    preview: String?,
    onPreview: (String?) -> Unit,
    onModels: () -> Unit,
    onChoose: (Capability, String) -> Unit,
    onOptions: (Capability) -> Unit,
    onReset: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val caps = remember(model) { model.configureCapabilities() }
    val effort = caps.firstOrNull { it.key == "effort" }
    val adjustable = remember(model, effort) { model.hasAdjustableEffort(effort) }
    val level = if (adjustable && effort != null) values[effort.key] ?: effort.choices.first() else null
    val speed = caps.firstOrNull { it.key == "speed" }
    val levels = remember(effort, model.profile.levels) {
        effort?.let { orderedEffortLevels(it.choices, model.profile.levels) }.orEmpty()
    }
    val shown = preview?.takeIf { adjustable && it in levels } ?: level
    val dark = colors.surface.luminance() < .5f
    val palette = remember(levels, shown, dark) { effortPalette(levels, shown, dark) }
    val accelerated = speeds.selected != null && speeds.selected == speeds.fast
    val titleTint by animateColorAsState(
        targetValue = when {
            level == null -> colors.onSurfaceVariant
            palette.isPeak -> PeakEffortLabelColor
            else -> palette.ink
        },
        animationSpec = tween(200, easing = EffortTintEase), label = "effortTitle",
    )
    val selectLabel = stringResource(R.string.chat_controls_select_model)
    val keepEditorFocus = Modifier.focusProperties { canFocus = false }

    Column(Modifier.fillMaxWidth()) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val buttonWidth = 48.dp
            val splitHeader = maxWidth < buttonWidth * 3
            val resetTop = if (maxWidth < buttonWidth * 2) buttonWidth else 0.dp
            val fastTint = when {
                palette.isPeak && dark -> Color(0xFFC39BFF)
                palette.isPeak -> Color(0xFF9B48EB)
                dark -> Color(0xFF7AAAFF)
                else -> Color(0xFF3988F6)
            }
            FastButton(speeds.fast, speeds.off, speeds.selected, buttonWidth, fastTint, Modifier.align(Alignment.TopStart)) {
                speed?.let { cap -> onChoose(cap, it) }
            }
            val sideInset = if (splitHeader) 0.dp else buttonWidth
            EffortHeaderLabels(
                title = if (effort != null && shown != null) chatOptionLabel(effort, shown) else stringResource(R.string.chat_controls_default),
                modelName = model.name, titleTint = titleTint, cardWidth = maxWidth, startInset = sideInset,
                besideButtons = !splitHeader,
                modifier = Modifier.fillMaxWidth().padding(
                    start = sideInset, end = sideInset, top = if (splitHeader) resetTop + buttonWidth else 0.dp,
                ).then(keepEditorFocus).testTag("chat-model-select")
                    .clickable(role = Role.Button, onClickLabel = selectLabel, onClick = onModels),
            )
            if (!splitHeader) {
                // The name can extend beneath the side buttons. Extend its touch surface only
                // below their 48dp targets; the central button remains the sole accessibility action.
                EffortHeaderSideTouches(Modifier.matchParentSize().padding(top = buttonWidth),
                    sideInset, onModels)
            }
            val resetLabel = stringResource(R.string.chat_controls_reset)
            val resetAction = if (level == null) Modifier.semantics { disabled() }
                else Modifier.clickable(role = Role.Button, onClick = onReset)
            Box(Modifier.align(Alignment.TopEnd).offset(y = resetTop).then(keepEditorFocus).width(buttonWidth).height(48.dp).testTag("chat-model-reset")
                .semantics { contentDescription = resetLabel }.then(resetAction), contentAlignment = Alignment.Center) {
                EffortToolGlyph(resetGlyph, Modifier.offset(x = 3.dp),
                    tint = { if (level == null) colors.onSurfaceVariant.copy(alpha = .38f) else colors.onSurfaceVariant })
            }
        }
        if (effort != null && level != null) {
            EffortTrack(effort, levels, level, peakEffortIndex(levels), accelerated,
                onPreview = onPreview, onChoose = { onChoose(effort, it) })
        } else FixedEffortTrack()
        Spacer(Modifier.height(2.dp))
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

/** The fallback Auto sent by a typed profile isn't a second user-configured level. */
internal fun ProviderModel.hasAdjustableEffort(capability: Capability?): Boolean {
    if (capability == null || capability.choices.size < 2) return false
    if (profile.type == null) return true
    val declared = profile.levels.filter(String::isNotBlank).ifEmpty {
        capabilities.firstOrNull { it.key == "effort" }?.choices.orEmpty()
    }
    val nonAuto = capability.choices.count { !it.equals("Auto", ignoreCase = true) }
    return nonAuto > 1 || (nonAuto == 1 && declared.any { it.equals("Auto", ignoreCase = true) })
}

internal fun orderedEffortLevels(choices: List<String>, declared: List<String>): List<String> {
    val knownOrder = effortLabels.keys.toList()
    if (choices.all { it.effortKey() in effortLabels }) return choices.sortedBy { knownOrder.indexOf(it.effortKey()) }
    // configureCapabilities puts the default first. Recover the configured order of custom levels.
    return (choices.filter { it.effortKey() in nonStrengths } + declared.filter { it in choices } + choices).distinct()
}

@Composable
private fun FastButton(tier: String?, off: String?, selected: String?, width: Dp, activeTint: Color,
    modifier: Modifier, onChange: (String) -> Unit) {
    val enabled = tier != null && off != null
    val active = tier != null && selected == tier
    val name = stringResource(R.string.chat_controls_fast)
    val description = when {
        enabled -> name
        active -> stringResource(R.string.chat_controls_fixed, name)
        else -> stringResource(R.string.chat_controls_not_connected, name)
    }
    val colors = MaterialTheme.colorScheme
    val targetTint = when {
        active -> activeTint.copy(alpha = if (enabled) 1f else .65f)
        !enabled -> colors.onSurface.copy(alpha = .32f)
        else -> colors.onSurfaceVariant
    }
    val tint = animateColorAsState(targetTint, tween(250, easing = EffortTintEase), label = "fastBoltTint")
    val fill = animateFloatAsState(if (active) 1f else 0f,
        tween(250, easing = EffortTintEase), label = "fastBoltFill")
    Box(modifier.width(width).height(48.dp).focusProperties { canFocus = false }
        .testTag("chat-model-fast")
        .semantics { contentDescription = description }
        .toggleable(active, enabled = enabled, role = Role.Switch) { on ->
            (if (on) tier else off)?.let(onChange)
        }, contentAlignment = Alignment.Center) {
        EffortToolGlyph(boltGlyph, Modifier.offset(x = (-3).dp).testTag("chat-model-fast-glyph"), tint = { tint.value }, fill = { fill.value })
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
private fun EffortToolGlyph(path: Path, modifier: Modifier, tint: () -> Color, fill: () -> Float = { 0f }) {
    Canvas(modifier.size(16.dp)) {
        scale(size.width / 24f, size.height / 24f, Offset.Zero) {
            val ink = tint()
            val amount = fill()
            if (amount > 0f) drawPath(path, ink, alpha = amount)
            drawPath(path, ink, style = Stroke(1.7f - amount, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    }
}
