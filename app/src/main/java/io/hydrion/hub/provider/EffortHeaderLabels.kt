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
import androidx.compose.ui.res.stringResource
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
import io.hydrion.hub.R
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

/** The labels share one action. A long name gets the card width below the side buttons. */
@Composable
internal fun EffortHeaderLabels(
    title: String, modelName: String, titleTint: Color, cardWidth: Dp, startInset: Dp,
    besideButtons: Boolean, modifier: Modifier, action: Modifier, onModels: () -> Unit,
) {
    val titleStyle = MaterialTheme.typography.bodyLarge.copy(fontSize = 16.sp, lineHeight = 22.sp,
        letterSpacing = 0.sp, textAlign = TextAlign.Center)
    val nameStyle = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp, lineHeight = 20.sp,
        letterSpacing = 0.sp, textAlign = TextAlign.Center)
    val namePaint = rememberInkPaint(nameStyle)
    val textMeasurer = rememberTextMeasurer()
    // Reserve one localized typographic line across all levels. A descender in "High"
    // must not push the model name, chevron, and track away from their resting positions.
    val reference = stringResource(R.string.chat_controls_default)
    val titleLine = remember(textMeasurer, reference, titleStyle) {
        textMeasurer.measure(reference, titleStyle, maxLines = 1, softWrap = false)
    }
    val secondary = MaterialTheme.colorScheme.onSurfaceVariant
    Layout(modifier = modifier, content = {
        Text(title, Modifier.testTag("chat-model-effort-title"), color = titleTint, style = titleStyle,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(modelName, Modifier.testTag("chat-model-name"), color = secondary, style = nameStyle,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
        Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null,
            Modifier.size(14.dp).testTag("chat-model-name-chevron"), tint = secondary)
        CompositionLocalProvider(LocalViewConfiguration provides exactPickerHitBounds()) {
            Spacer(action)
            val latest = rememberUpdatedState(onModels)
            // A long label may extend under a side button; only its visible lower strip takes taps.
            repeat(2) { Spacer(Modifier.pointerInput(Unit) { detectTapGestures { latest.value() } }) }
        }
    }) { parts, limits ->
        val fullWidth = cardWidth.roundToPx()
        val centerX = fullWidth / 2
        val buttonHeight = if (besideButtons) 48.dp.roundToPx() else 0
        val inlineWidth = (fullWidth - 2 * startInset.roundToPx()).coerceAtLeast(0)
        val arrow = parts[2].measure(Constraints())
        val gap = 3.dp.roundToPx()
        val inlineNameWidth = inlineWidth - 2 * (arrow.width + gap)
        val wideLine = besideButtons && parts[1].maxIntrinsicWidth(Constraints.Infinity) > inlineNameWidth
        val nameWidth = if (wideLine || !besideButtons) (fullWidth - 2 * (arrow.width + gap)).coerceAtLeast(0)
            else inlineWidth
        val heading = parts[0].measure(Constraints(maxWidth = inlineWidth))
        val name = parts[1].measure(Constraints(maxWidth = nameWidth))
        val titleHeight = titleLine.size.height
        val baseHeight = maxOf(60.dp.roundToPx(), titleHeight + name.height + 8.dp.roundToPx())
        val titleY = 6.dp.roundToPx() + (baseHeight - 8.dp.roundToPx() - titleHeight - name.height) / 2
        val nameLine = textMeasurer.measure(modelName, nameStyle, overflow = TextOverflow.Ellipsis, maxLines = 1,
            constraints = Constraints(maxWidth = nameWidth))
        val visibleName = if (nameLine.isLineEllipsized(0)) modelName.take(nameLine.getLineEnd(0, visibleEnd = true)) + "…" else modelName
        val nameInk = Rect().also { namePaint.getTextBounds(visibleName, 0, visibleName.length, it) }
        val titleBaseline = titleY + titleLine.firstBaseline.roundToInt()
        val nameInkSum = 2 * name[FirstBaseline] + nameInk.top + nameInk.bottom
        // The visible 26dp rail is centered in a 48dp hit row. Use its top, not the thumb or hit row.
        val railInset = ((EffortRailHitHeight - EffortRailHeight) / 2).roundToPx()
        val firstNameInk = minOf(name[FirstBaseline] + nameInk.top, (name.height - arrow.height) / 2)
        val minNameY = maxOf(titleY + titleHeight, if (wideLine) buttonHeight - firstNameInk else 0)
        val height = limits.constrainHeight(maxOf(baseHeight,
            2 * minNameY + nameInkSum - titleBaseline - railInset,
            titleBaseline + railInset - nameInkSum + 2 * name.height))
        val lastY = (height - name.height).coerceAtLeast(0)
        val nameY = ((titleBaseline + height + railInset - nameInkSum) / 2f).roundToInt()
            .coerceIn(minOf(minNameY, lastY), lastY)
        val nameX = centerX - name.width / 2
        val arrowEnd = nameX + name.width + gap + arrow.width
        val side = startInset.roundToPx()
        val minTouch = 48.dp.roundToPx()
        val gutter = minOf(6.dp.roundToPx(), (fullWidth - minTouch).coerceAtLeast(0) / 2)
        val pad = 6.dp.roundToPx()
        val hitStart = minOf(nameX - pad, centerX - minTouch / 2).coerceAtLeast(gutter)
        val hitEnd = maxOf(arrowEnd + pad, centerX + (minTouch + 1) / 2).coerceAtMost(fullWidth - gutter)
        val primaryStart = maxOf(hitStart, side)
        val primaryEnd = minOf(hitEnd, fullWidth - side)
        val main = parts[3].measure(Constraints.fixed(primaryEnd - primaryStart, height))
        val lowerHeight = (height - buttonHeight).coerceAtLeast(0)
        val startWing = parts[4].measure(Constraints.fixed(primaryStart - hitStart, lowerHeight))
        val endWing = parts[5].measure(Constraints.fixed(hitEnd - primaryEnd, lowerHeight))
        layout(limits.maxWidth, height) {
            heading.placeRelative(centerX - heading.width / 2, titleBaseline - heading[FirstBaseline])
            name.placeRelative(nameX, nameY)
            arrow.placeRelative(nameX + name.width + gap, nameY + (name.height - arrow.height) / 2)
            main.placeRelative(primaryStart, 0)
            if (startWing.width > 0) startWing.placeRelative(hitStart, buttonHeight)
            if (endWing.width > 0) endWing.placeRelative(primaryEnd, buttonHeight)
        }
    }
}
