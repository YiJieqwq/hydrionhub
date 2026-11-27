package io.hydrion.hub.provider

import android.icu.text.BreakIterator
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.hydrion.hub.R
import io.hydrion.hub.ui.theme.HubShapes

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
    Text(label, Modifier.padding(start = 14.dp, end = 14.dp, top = 8.dp, bottom = 4.dp).semantics { heading() },
        maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 11.5.sp, lineHeight = 17.sp,
        letterSpacing = .25.sp, fontWeight = FontWeight.Medium, color = browseColors().heading)
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun BrowseTile(
    label: String, provider: Provider, model: ProviderModel, checked: Boolean,
    modifier: Modifier, compact: Boolean = false, onEdit: (() -> Unit)? = null, favoriteButton: (@Composable () -> Unit)? = null, onPick: () -> Unit,
) {
    val colors = browseColors()
    val shape = HubShapes.Small
    val action = modifier.focusProperties { canFocus = false }
        .semantics { selected = checked }
        .combinedClickable(role = Role.RadioButton,
            onLongClickLabel = if (onEdit != null) stringResource(R.string.chat_picker_edit_model) else null,
            onLongClick = onEdit, onClick = onPick)
    // The chat panel already supplies a 12dp list inset. Settings owns its own tile margins.
    Box(Modifier.padding(horizontal = if (compact) 0.dp else 12.dp, vertical = if (compact) 3.dp else 6.dp)) {
        Row(Modifier.fillMaxWidth().clip(shape).background(if (checked) colors.selected else Color.Transparent),
            verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f).then(action).heightIn(min = 48.dp)
                .padding(start = if (compact) 8.dp else 12.dp, end = if (favoriteButton == null) 12.dp else 0.dp)
                .padding(vertical = if (compact) 6.dp else 10.dp),
                verticalAlignment = Alignment.CenterVertically) {
                BrowseAvatar(provider, model, if (compact) 24.dp else 28.dp, colors)
                Spacer(Modifier.width(if (compact) 8.dp else 10.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(if (compact) 2.dp else 4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(label, Modifier.weight(1f), fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = .25.sp,
                            fontWeight = FontWeight.SemiBold, color = colors.text)
                        if (compact && checked) Icon(painterResource(R.drawable.lucide_check), null,
                            Modifier.padding(start = 4.dp).size(16.dp), tint = colors.primary)
                    }
                    BrowseModelTags(model, provider, colors, compact)
                }
                if (!compact) Box(Modifier.size(if (favoriteButton == null) 36.dp else 22.dp), contentAlignment = Alignment.Center) {
                    if (checked) Icon(painterResource(R.drawable.lucide_check), null, Modifier.size(20.dp), tint = colors.primary)
                }
            }
            favoriteButton?.invoke()
        }
    }
}

@Composable
internal fun ModelFavoriteButton(checked: Boolean, modelLabel: String, modifier: Modifier, onChange: (Boolean) -> Unit) {
    val inputMode = LocalInputModeManager.current
    val action = stringResource(if (checked) R.string.browse_remove_favorite else R.string.browse_add_favorite, modelLabel)
    IconToggleButton(checked, onChange, modifier.size(48.dp)
        .focusProperties { canFocus = inputMode.inputMode != InputMode.Touch }) {
        Icon(if (checked) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder, action, Modifier.size(20.dp),
            tint = browseColors().primary)
    }
}

@Composable
internal fun BrowseAvatar(provider: Provider, model: ProviderModel?, size: Dp, colors: BrowseColors) {
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
private fun BrowseModelTags(model: ProviderModel, provider: Provider, colors: BrowseColors, compact: Boolean) {
    val type = model.typeFor(provider.mode)
    val typeLabel = when (type) {
        ModelType.Chat -> R.string.browse_chat
        ModelType.Image -> R.string.browse_image
        ModelType.Embedding -> R.string.browse_embedding
    }
    val modalFill = if (colors.dark) .25f else .15f
    val iconAlpha = if (colors.dark) 1f else .9f
    val gap = if (compact) 4.dp else 6.dp
    val pad = if (compact) 5.dp else 8.dp
    val iconPad = if (compact) 4.dp else 6.dp
    FlowRow(horizontalArrangement = Arrangement.spacedBy(gap), verticalArrangement = Arrangement.spacedBy(gap)) {
        BadgeSurface(colors.primary, modalFill, .2f, pad, Modifier.align(Alignment.CenterVertically)) {
            BadgeText(stringResource(typeLabel), colors.primary.copy(alpha = iconAlpha))
        }
        BadgeSurface(colors.tertiary, modalFill, .2f, pad, Modifier.align(Alignment.CenterVertically)) {
            val ink = colors.tertiary.copy(alpha = iconAlpha)
            ModalityIcons(model.profile.input.ifEmpty { setOf("text") }, ink)
            Icon(painterResource(R.drawable.lucide_chevron_right), stringResource(R.string.browse_outputs),
                Modifier.size(12.dp), tint = ink)
            ModalityIcons(model.profile.output.ifEmpty { setOf("text") }, ink)
        }
        if ("tools" in model.profile.abilities) BadgeSurface(colors.primary, modalFill, .2f, iconPad, Modifier.align(Alignment.CenterVertically)) {
            Icon(painterResource(R.drawable.lucide_hammer), stringResource(R.string.browse_tools),
                Modifier.size(12.dp), tint = colors.primary.copy(alpha = iconAlpha))
        }
        if ("reasoning" in model.profile.abilities || model.configureCapabilities().any { it.key == "effort" }) {
            BadgeSurface(colors.secondary, if (colors.dark) .30f else .18f, .25f, iconPad, Modifier.align(Alignment.CenterVertically)) {
                Icon(painterResource(R.drawable.lucide_brain), stringResource(R.string.browse_reasoning),
                    Modifier.size(12.dp), tint = colors.secondary.copy(alpha = iconAlpha))
            }
        }
        val size = model.contextWindowTokens
        BadgeSurface(colors.text, if (colors.dark) .12f else .08f, .16f, pad, Modifier.align(Alignment.CenterVertically)) {
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
