package io.hydrion.hub.provider

import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.FirstBaseline
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFontFamilyResolver
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontSynthesis
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

/** Android glyph ink relative to the first baseline, using the same resolved face and size as Text. */
@Composable
private fun rememberInkPaint(style: TextStyle): Paint {
    val face = LocalFontFamilyResolver.current.resolve(style.fontFamily, style.fontWeight ?: FontWeight.Normal,
        style.fontStyle ?: FontStyle.Normal, style.fontSynthesis ?: FontSynthesis.All).value as Typeface
    val pixels = with(LocalDensity.current) { style.fontSize.toPx() }
    return remember(face, pixels) {
        Paint(Paint.ANTI_ALIAS_FLAG).also { it.typeface = face; it.textSize = pixels }
    }
}

/** The labels share one action. A long name gets the card width below the two leading buttons. */
@Composable
internal fun EffortHeaderLabels(
    title: String, modelName: String, titleTint: Color, cardWidth: Dp, startInset: Dp,
    besideButtons: Boolean, hasRail: Boolean, modifier: Modifier,
) {
    val titleStyle = MaterialTheme.typography.bodyLarge.copy(fontSize = 16.sp, lineHeight = 22.sp,
        letterSpacing = 0.sp, textAlign = TextAlign.Center)
    val nameStyle = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp, lineHeight = 20.sp,
        letterSpacing = 0.sp, textAlign = TextAlign.Center)
    val titlePaint = rememberInkPaint(titleStyle)
    val namePaint = rememberInkPaint(nameStyle)
    val textMeasurer = rememberTextMeasurer()
    val secondary = MaterialTheme.colorScheme.onSurfaceVariant
    Layout(modifier = modifier, content = {
        Text(title, Modifier.testTag("chat-model-effort-title"), color = titleTint, style = titleStyle,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(modelName, Modifier.testTag("chat-model-name"), color = secondary, style = nameStyle,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
        Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null,
            Modifier.size(14.dp).testTag("chat-model-name-chevron"), tint = secondary)
    }) { parts, limits ->
        val fullWidth = cardWidth.roundToPx()
        val centerX = fullWidth / 2 - startInset.roundToPx()
        val buttonHeight = if (besideButtons) 48.dp.roundToPx() else 0
        val inlineWidth = (fullWidth - 2 * startInset.roundToPx()).coerceAtLeast(0)
        val arrow = parts[2].measure(Constraints())
        val gap = 3.dp.roundToPx()
        val wideLine = besideButtons && parts[1].maxIntrinsicWidth(Constraints.Infinity) > inlineWidth
        val nameWidth = if (wideLine || !besideButtons) (fullWidth - 2 * (arrow.width + gap)).coerceAtLeast(0)
            else inlineWidth
        val heading = parts[0].measure(Constraints(maxWidth = inlineWidth))
        val name = parts[1].measure(Constraints(maxWidth = nameWidth))
        val baseHeight = maxOf(60.dp.roundToPx(), heading.height + name.height + 8.dp.roundToPx())
        val titleY = 6.dp.roundToPx() + (baseHeight - 8.dp.roundToPx() - heading.height - name.height) / 2
        fun ink(text: String, style: TextStyle, width: Int, paint: Paint): Rect {
            val line = textMeasurer.measure(text, style, overflow = TextOverflow.Ellipsis, maxLines = 1,
                constraints = Constraints(maxWidth = width))
            val visible = if (line.isLineEllipsized(0)) text.take(line.getLineEnd(0, visibleEnd = true)) + "…" else text
            return Rect().also { paint.getTextBounds(visible, 0, visible.length, it) }
        }
        val titleInk = ink(title, titleStyle, inlineWidth, titlePaint)
        val nameInk = ink(modelName, nameStyle, nameWidth, namePaint)
        val titleBottom = titleY + heading[FirstBaseline] + titleInk.bottom
        val nameInkSum = 2 * name[FirstBaseline] + nameInk.top + nameInk.bottom
        // The visible 26dp rail is centered in a 48dp hit row. Use its top, not the thumb or hit row.
        val railInset = if (hasRail) ((EffortRailHitHeight - EffortRailHeight) / 2).roundToPx() else 0
        val firstNameInk = minOf(name[FirstBaseline] + nameInk.top, (name.height - arrow.height) / 2)
        val minNameY = maxOf(titleY + heading.height, if (wideLine) buttonHeight - firstNameInk else 0)
        val height = limits.constrainHeight(maxOf(baseHeight,
            2 * minNameY + nameInkSum - titleBottom - railInset,
            titleBottom + railInset - nameInkSum + 2 * name.height))
        val lastY = (height - name.height).coerceAtLeast(0)
        val nameY = ((titleBottom + height + railInset - nameInkSum) / 2f).roundToInt()
            .coerceIn(minOf(minNameY, lastY), lastY)
        layout(limits.maxWidth, height) {
            heading.placeRelative(centerX - heading.width / 2, titleY)
            val nameX = centerX - name.width / 2
            name.placeRelative(nameX, nameY)
            arrow.placeRelative(nameX + name.width + gap, nameY + (name.height - arrow.height) / 2)
        }
    }
}

@Composable
internal fun EffortHeaderSideTouches(modifier: Modifier, start: Dp, end: Dp, onModels: () -> Unit) {
    val action = rememberUpdatedState(onModels)
    val touch = Modifier.fillMaxHeight().pointerInput(Unit) { detectTapGestures { action.value() } }
    CompositionLocalProvider(LocalViewConfiguration provides exactPickerHitBounds()) {
        Row(modifier) {
            Spacer(touch.width(start))
            Spacer(Modifier.weight(1f))
            Spacer(touch.width(end))
        }
    }
}
