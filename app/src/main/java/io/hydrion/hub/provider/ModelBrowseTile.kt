package io.hydrion.hub.provider

import android.icu.text.BreakIterator
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.hydrion.hub.R

// Default mobile picker palette. Scope these roles to the list so they cannot recolor the composer.
internal class BrowseColors(val dark: Boolean, val primary: Color, val secondary: Color, val tertiary: Color, val text: Color) {
    val heading = text.copy(alpha = .6f)
    val selected = primary.copy(alpha = if (dark) .12f else .08f)
    val avatar = primary.copy(alpha = if (dark) .18f else .10f)
}
private val darkBrowse = BrowseColors(true, Color(0xFFB6C4FF), Color(0xFFC2C5DD), Color(0xFFE3BADA), Color(0xFFF9F9F9))
private val lightBrowse = BrowseColors(false, Color(0xFF4D5C92), Color(0xFF595D72), Color(0xFF75546F), Color(0xFF202020))

@Composable
internal fun browseColors(): BrowseColors = with(MaterialTheme.colorScheme) {
    if (onSurface.luminance() > surface.luminance()) darkBrowse else lightBrowse
}

@Composable
internal fun BrowseHeading(label: String) {
    Text(label, Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 8.dp).semantics { heading() },
        maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 12.sp, lineHeight = 17.sp,
        letterSpacing = .25.sp, fontWeight = FontWeight.Medium, color = browseColors().heading)
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun BrowseTile(
    label: String, provider: Provider, model: ProviderModel?, checked: Boolean?,
    modifier: Modifier, onEdit: (() -> Unit)? = null, onPick: () -> Unit,
) {
    val colors = browseColors()
    val shape = RoundedCornerShape(14.dp)
    val action = modifier.focusProperties { canFocus = false }
        .semantics { if (checked != null) selected = checked }
        .combinedClickable(role = if (checked == null) Role.Button else Role.RadioButton,
            onLongClickLabel = if (onEdit != null) stringResource(R.string.chat_picker_edit_model) else null,
            onLongClick = onEdit, onClick = onPick)
    if (model == null) {
        Box(Modifier.padding(horizontal = 16.dp)) {
            Box(Modifier.fillMaxWidth().then(action).heightIn(min = 48.dp), contentAlignment = Alignment.CenterStart) {
                val fill = if (checked == false) Color.Transparent else colors.primary.copy(alpha = if (colors.dark) .08f else .05f)
                val outline = (if (colors.dark) Color.White else Color.Black).copy(alpha = .25f)
                Row(Modifier.clip(shape).background(fill).border(1.dp, outline, shape).padding(horizontal = 10.dp, vertical = 7.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    BrowseAvatar(provider, null, 18.dp, colors)
                    Text(label, Modifier.weight(1f, fill = false), maxLines = 1, overflow = TextOverflow.Ellipsis,
                        fontSize = 12.sp, lineHeight = 17.sp, letterSpacing = .25.sp, color = colors.text)
                    if (checked != false) Icon(painterResource(if (checked == null) R.drawable.lucide_chevron_right else R.drawable.lucide_check),
                        null, Modifier.size(14.dp), tint = colors.primary)
                }
            }
        }
        return
    }
    Box(Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
        Row(Modifier.fillMaxWidth().clip(shape).background(if (checked == true) colors.selected else Color.Transparent)
            .then(action).heightIn(min = 48.dp).padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically) {
            BrowseAvatar(provider, model, 28.dp, colors)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(label, fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = .25.sp,
                    fontWeight = FontWeight.Medium, color = colors.text)
                BrowseModelTags(model, provider, colors)
            }
            // Keep labels and badges the same width on selected and unselected rows.
            Box(Modifier.size(36.dp), contentAlignment = Alignment.Center) {
                if (checked == true) Icon(painterResource(R.drawable.lucide_check), null, Modifier.size(20.dp), tint = colors.primary)
            }
        }
    }
}

@Composable
private fun BrowseAvatar(provider: Provider, model: ProviderModel?, size: Dp, colors: BrowseColors) {
    val ring = if (model == null) Modifier.border(.5.dp, colors.text.copy(alpha = if (colors.dark) .24f else .12f), CircleShape) else Modifier
    Box(Modifier.size(size).background(colors.avatar, CircleShape).then(ring), contentAlignment = Alignment.Center) {
        val iconSize = Modifier.size(size * (if (model == null) .7f else .62f))
        when {
            model != null && model.profile.icon.isNotEmpty() -> ProfileIcon(model.profile.icon, model.typeFor(provider.mode), iconSize)
            provider.id == DEEPSEEK_PROVIDER_ID -> Image(painterResource(R.drawable.deepseek_whale), null, iconSize)
            else -> {
                val name = model?.name ?: provider.name
                val initial = remember(name) {
                    if (name.isEmpty()) "?" else name.take(BreakIterator.getCharacterInstance().apply { setText(name) }.next()).uppercase()
                }
                Text(initial, Modifier.clearAndSetSemantics {}, fontSize = (size.value * .43f).sp,
                    lineHeight = size.value.sp, fontWeight = FontWeight.Medium, color = colors.primary)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BrowseModelTags(model: ProviderModel, provider: Provider, colors: BrowseColors) {
    val type = model.typeFor(provider.mode)
    val typeLabel = when (type) {
        ModelType.Chat -> R.string.browse_chat
        ModelType.Image -> R.string.browse_image
        ModelType.Embedding -> R.string.browse_embedding
    }
    val modalFill = if (colors.dark) .25f else .15f
    val iconAlpha = if (colors.dark) 1f else .9f
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        BadgeSurface(colors.primary, modalFill, .2f, 8.dp, Modifier.align(Alignment.CenterVertically)) {
            BadgeText(stringResource(typeLabel), colors.primary.copy(alpha = iconAlpha))
        }
        BadgeSurface(colors.tertiary, modalFill, .2f, 8.dp, Modifier.align(Alignment.CenterVertically)) {
            val ink = colors.tertiary.copy(alpha = iconAlpha)
            ModalityIcons(model.profile.input.ifEmpty { setOf("text") }, ink)
            Icon(painterResource(R.drawable.lucide_chevron_right), stringResource(R.string.browse_outputs),
                Modifier.size(12.dp), tint = ink)
            ModalityIcons(model.profile.output.ifEmpty { setOf("text") }, ink)
        }
        if ("tools" in model.profile.abilities) BadgeSurface(colors.primary, modalFill, .2f, 6.dp, Modifier.align(Alignment.CenterVertically)) {
            Icon(painterResource(R.drawable.lucide_hammer), stringResource(R.string.browse_tools),
                Modifier.size(12.dp), tint = colors.primary.copy(alpha = iconAlpha))
        }
        if ("reasoning" in model.profile.abilities || model.configureCapabilities().any { it.key == "effort" }) {
            BadgeSurface(colors.secondary, if (colors.dark) .30f else .18f, .25f, 6.dp, Modifier.align(Alignment.CenterVertically)) {
                Icon(painterResource(R.drawable.lucide_brain), stringResource(R.string.browse_reasoning),
                    Modifier.size(12.dp), tint = colors.secondary.copy(alpha = iconAlpha))
            }
        }
        val size = model.contextWindowTokens
        BadgeSurface(colors.text, if (colors.dark) .12f else .08f, .16f, 8.dp, Modifier.align(Alignment.CenterVertically)) {
            val context = stringResource(R.string.browse_context, size)
            BadgeText(browseTokenCount(size), colors.text.copy(alpha = if (colors.dark) .86f else .78f),
                Modifier.clearAndSetSemantics { contentDescription = context })
        }
    }
}

internal fun browseTokenCount(tokens: Int): String {
    if (tokens < 1000) return tokens.toString()
    val millions = tokens >= 1_000_000
    val tenths = tokens / (if (millions) 100_000 else 100)
    val amount = if (tenths % 10 == 0) (tenths / 10).toString() else "${tenths / 10}.${tenths % 10}"
    return amount + if (millions) "M" else "k"
}

@Composable
private fun BadgeText(text: String, ink: Color, modifier: Modifier = Modifier) {
    Text(text, modifier, color = ink, fontSize = 11.sp, lineHeight = 16.sp, letterSpacing = .25.sp,
        fontWeight = FontWeight.Normal)
}

@Composable
private fun ModalityIcons(types: Set<String>, ink: Color) {
    for (type in types) {
        val (icon, label) = when (type) {
            "text" -> R.drawable.lucide_type to R.string.browse_text
            "image" -> R.drawable.lucide_image to R.string.browse_image
            "audio" -> R.drawable.lucide_audio_lines to R.string.browse_audio
            "video" -> R.drawable.lucide_file_video to R.string.browse_video
            "pdf" -> R.drawable.lucide_file_text to R.string.browse_pdf
            else -> continue
        }
        Icon(painterResource(icon), stringResource(label), Modifier.size(12.dp), tint = ink)
    }
}

@Composable
private fun BadgeSurface(tint: Color, fillAlpha: Float, borderAlpha: Float, pad: Dp, modifier: Modifier,
                         content: @Composable RowScope.() -> Unit) {
    Row(modifier.background(tint.copy(alpha = fillAlpha), CircleShape).border(.5.dp, tint.copy(alpha = borderAlpha), CircleShape)
        .padding(horizontal = pad + .5.dp, vertical = 3.5.dp), horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically, content = content)
}
