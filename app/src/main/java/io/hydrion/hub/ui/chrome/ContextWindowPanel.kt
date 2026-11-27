package io.hydrion.hub.ui.chrome

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.AccountTree
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.hydrion.hub.R
import io.hydrion.hub.ui.theme.HubShapes
import java.text.NumberFormat
import java.util.Locale

private val ContextPart.label: Int get() = when (this) {
    ContextPart.Messages -> R.string.context_messages
    ContextPart.Tools -> R.string.context_tools
    ContextPart.Skills -> R.string.context_skills
    ContextPart.System -> R.string.context_system
    ContextPart.Memory -> R.string.context_memory
    ContextPart.Workspace -> R.string.context_workspace
    ContextPart.Search -> R.string.context_search
    ContextPart.Unattributed -> R.string.context_unattributed
    ContextPart.Free -> R.string.context_free
}

private fun ContextPart.color(dark: Boolean): Color = when (this) {
    ContextPart.Messages -> Color(0xFF3B82F6)
    ContextPart.Tools -> Color(0xFFF59E0B)
    ContextPart.Skills -> Color(0xFFEAB308)
    ContextPart.System -> Color(0xFF909096)
    ContextPart.Memory -> Color(0xFF14B8A6)
    ContextPart.Workspace -> Color(0xFF64748B)
    ContextPart.Search -> Color(0xFF06B6D4)
    ContextPart.Unattributed -> if (dark) Color(0xFFA79AD1) else Color(0xFF736498)
    ContextPart.Free -> if (dark) Color(0xFF383838) else Color(0xFFCED3DC)
}

private class ContextNumbers(locale: Locale) {
    private val counts = NumberFormat.getIntegerInstance(locale)
    private val compact = NumberFormat.getNumberInstance(locale).apply { maximumFractionDigits = 1 }
    private val percent = NumberFormat.getPercentInstance(locale).apply { maximumFractionDigits = 1 }

    fun exact(count: Long) = counts.format(count)
    fun count(count: Long): String = when {
        count >= 1_000_000 -> compact.format(count / 1_000_000.0) + "M"
        count >= 10_000 -> compact.format(count / 1_000.0) + "k"
        else -> exact(count)
    }
    fun share(fraction: Double): String {
        if (fraction > 0 && fraction < 0.001) return "<" + percent.format(0.001)
        val label = percent.format(fraction)
        if (fraction != 1.0 && label == percent.format(1)) return (if (fraction < 1) "<" else ">") + label
        return label
    }
}

@Composable
internal fun ContextWindowPanel(usage: ContextUsage, onManage: (() -> Unit)?, modifier: Modifier = Modifier) {
    val locale = LocalConfiguration.current.locales[0]
    val numbers = remember(locale) { ContextNumbers(locale) }
    val colors = MaterialTheme.colorScheme
    val dark = colors.surface.luminance() < colors.onSurface.luminance()
    val unknown = stringResource(R.string.context_unmeasured)
    val limit = usage.window?.toLong()
    val unknownLimit = stringResource(R.string.context_unknown_limit)
    val ratio = stringResource(R.string.context_used_of, usage.tokens?.let(numbers::count) ?: "—", limit?.let(numbers::count) ?: unknownLimit)
    val exact = stringResource(R.string.context_used_of, usage.tokens?.let(numbers::exact) ?: unknown, limit?.let(numbers::exact) ?: unknownLimit)
    val share = usage.share(usage.tokens)?.let(numbers::share)
    val parts = remember(usage) { ContextPart.entries.filter { it != ContextPart.Unattributed || usage.count(it) != null } }
    Column(modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 12.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.context_window), Modifier.weight(1f), fontSize = 17.sp,
                lineHeight = 21.sp, fontWeight = FontWeight.SemiBold, color = colors.onSurface)
            Column(Modifier.weight(1.2f), horizontalAlignment = Alignment.End) {
                Text(if (share == null) ratio else stringResource(R.string.context_used_share, ratio, share),
                    Modifier.semantics { stateDescription = exact }, fontSize = 14.sp, lineHeight = 18.sp, textAlign = TextAlign.End,
                    fontWeight = FontWeight.Medium, color = colors.onSurface)
                Text(stringResource(when {
                    usage.estimated -> R.string.context_estimated
                    usage.tokens != null -> R.string.context_reported
                    else -> R.string.context_unreported
                }), style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant, textAlign = TextAlign.End)
            }
        }
        Spacer(Modifier.height(12.dp))
        Canvas(Modifier.fillMaxWidth().height(8.dp).clip(CircleShape).testTag("context-segments")) {
            // A neutral track with no colored segment means the share is unknown.
            drawRect(colors.outlineVariant.copy(alpha = .4f))
            var used = 0f
            for (part in parts) {
                val amount = usage.share(usage.count(part)) ?: continue
                val width = (size.width * amount.coerceIn(0.0, 1.0)).toFloat().coerceAtMost((size.width - used).coerceAtLeast(0f))
                val x = if (layoutDirection == LayoutDirection.Ltr) used else size.width - used - width
                if (width > 0f) drawRect(part.color(dark), Offset(x, 0f), Size(width, size.height))
                used += width
            }
        }
        Spacer(Modifier.height(10.dp))
        for (part in parts) {
            val amount = usage.count(part)
            val portion = usage.share(amount)
            Row(Modifier.fillMaxWidth().heightIn(min = 27.dp).testTag("context-part-" + part.name).semantics(mergeDescendants = true) {},
                verticalAlignment = Alignment.CenterVertically) {
                Spacer(Modifier.size(10.dp).background(part.color(dark), RoundedCornerShape(3.dp)))
                Spacer(Modifier.width(9.dp))
                Text(stringResource(part.label), Modifier.weight(1.65f), fontSize = 14.sp, lineHeight = 18.sp,
                    fontWeight = FontWeight.Medium, color = colors.onSurface)
                Text(amount?.let(numbers::count) ?: unknown, Modifier.weight(1f), fontSize = 13.sp, lineHeight = 17.sp,
                    color = colors.onSurfaceVariant, textAlign = TextAlign.End)
                Text(portion?.let(numbers::share).orEmpty(), Modifier.weight(.65f).padding(start = 4.dp),
                    fontSize = 13.sp, lineHeight = 17.sp, color = colors.onSurfaceVariant, textAlign = TextAlign.End)
            }
        }
        if (onManage != null) {
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth().clip(HubShapes.Small).clickable(role = Role.Button, onClick = onManage)
                .heightIn(min = 48.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.AccountTree, null, Modifier.size(19.dp), tint = colors.onSurface)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.context_management), fontSize = 15.sp, fontWeight = FontWeight.Medium, color = colors.onSurface)
                    Text(stringResource(R.string.context_manage_limit), style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                }
                Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null, Modifier.size(19.dp), tint = colors.onSurfaceVariant)
            }
        }
    }
}
