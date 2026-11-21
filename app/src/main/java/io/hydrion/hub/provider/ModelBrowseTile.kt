package io.hydrion.hub.provider

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.hydrion.hub.R

@Composable
internal fun BrowseHeading(label: String, color: Color) {
    Text(label, Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 8.dp).semantics { heading() },
        fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.SemiBold, color = color)
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun BrowseTile(
    label: String, provider: Provider, model: ProviderModel?, checked: Boolean?,
    modifier: Modifier, onEdit: (() -> Unit)? = null, onPick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val dark = colors.surface.luminance() < colors.onSurface.luminance()
    val tint = if (checked == true) colors.primary.copy(alpha = if (dark) .12f else .08f) else Color.Transparent
    val shape = RoundedCornerShape(14.dp)
    Box(Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
        Row(modifier.fillMaxWidth().clip(shape).background(tint).focusProperties { canFocus = false }
            .semantics { if (checked != null) selected = checked }
            .combinedClickable(role = if (checked == null) Role.Button else Role.RadioButton,
                onLongClickLabel = if (onEdit != null) stringResource(R.string.chat_picker_edit_model) else null,
                onLongClick = onEdit, onClick = onPick)
            .heightIn(min = 48.dp).padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.size(28.dp).background(colors.surfaceContainerHigh, CircleShape), contentAlignment = Alignment.Center) {
                when {
                    model?.profile?.icon?.isNotEmpty() == true -> ProfileIcon(model.profile.icon, model.typeFor(provider.mode), Modifier.size(18.dp))
                    provider.id == DEEPSEEK_PROVIDER_ID -> Image(painterResource(R.drawable.deepseek_whale), null, Modifier.size(18.dp))
                    model != null -> ProfileIcon("", model.typeFor(provider.mode), Modifier.size(18.dp))
                    else -> Icon(Icons.Outlined.Storage, null, Modifier.size(18.dp))
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(label, maxLines = 2, overflow = TextOverflow.Ellipsis, fontSize = 14.sp,
                    lineHeight = 19.sp, fontWeight = FontWeight.SemiBold,
                    color = if (dark && model != null) Color(0xFFEDEDED) else colors.onSurface)
                if (model != null) BrowseModelTags(model)
            }
            if (checked == true) Icon(Icons.Outlined.Check, null, Modifier.size(20.dp), tint = colors.primary)
            if (checked == null) Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null, Modifier.size(18.dp))
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BrowseModelTags(model: ProviderModel) {
    val colors = MaterialTheme.colorScheme
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        BadgeSurface(colors.tertiary) {
            ModalityIcons(model.profile.input)
            Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, stringResource(R.string.browse_outputs), Modifier.size(12.dp))
            ModalityIcons(model.profile.output.ifEmpty { setOf("text") })
        }
        if ("tools" in model.profile.abilities) BadgeSurface(colors.primary) {
            Icon(Icons.Outlined.Build, stringResource(R.string.browse_tools), Modifier.size(13.dp))
        }
        if ("reasoning" in model.profile.abilities || model.configureCapabilities().any { it.key == "effort" }) {
            BadgeSurface(colors.secondary) {
                Icon(Icons.Outlined.Psychology, stringResource(R.string.browse_reasoning), Modifier.size(13.dp))
            }
        }
        val size = model.contextWindowTokens
        val compact = when {
            size % 1_000_000 == 0 -> "${size / 1_000_000}M"
            size % 1_000 == 0 -> "${size / 1_000}K"
            else -> size.toString()
        }
        BadgeSurface(colors.primary) {
            val windowLabel = stringResource(R.string.browse_context, size)
            Text(compact, Modifier.clearAndSetSemantics { contentDescription = windowLabel }, fontSize = 11.sp, lineHeight = 14.sp)
        }
    }
}

@Composable
private fun ModalityIcons(types: Set<String>) {
    for (type in types) {
        val (icon, label) = when (type) {
            "text" -> Icons.Outlined.TextFields to R.string.browse_text
            "image" -> Icons.Outlined.Image to R.string.browse_image
            "audio" -> Icons.Outlined.Mic to R.string.browse_audio
            "video" -> Icons.Outlined.Videocam to R.string.browse_video
            "pdf" -> Icons.Outlined.PictureAsPdf to R.string.browse_pdf
            else -> continue
        }
        Icon(icon, stringResource(label), Modifier.size(13.dp))
    }
}

@Composable
private fun BadgeSurface(tint: Color, content: @Composable RowScope.() -> Unit) {
    Row(Modifier.background(tint.copy(alpha = .15f), CircleShape).border(.5.dp, tint.copy(alpha = .2f), CircleShape)
        .padding(horizontal = 7.dp, vertical = 3.dp), verticalAlignment = Alignment.CenterVertically, content = content)
}
