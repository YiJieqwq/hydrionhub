package io.hydrion.hub.ui.composer

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import io.hydrion.hub.R
import io.hydrion.hub.ui.theme.HubStyle
import kotlin.math.roundToInt

internal const val ChatDraftHint = "Chat with AI"
internal val FoldedTextStart = 48.dp
internal val OpenTextStart = 15.dp
internal val OpenTextEnd = 16.dp
internal val SearchIconSize = 18.dp
internal val SearchIconGap = 7.dp
internal val OpenTextTop = 16.dp
internal val TextBottom = 4.dp

// In the folded capsule the draft starts past the magnifier. Keep the icon anchored,
// and leave one normal gap beyond the draft origin for the query and its caret.
internal fun modelSearchIconGap(draftStart: Dp): Dp =
    SearchIconGap + (draftStart - OpenTextStart - SearchIconSize).coerceAtLeast(0.dp)

// Keep the active field's native Text measurement and single accessible hint during the handoff.
internal val MeasuredHintOnly = Modifier.drawWithContent {}

/** Draw in the already measured editor slot, below the caret. Neither label is an input node. */
@Composable
internal fun Modifier.modelSearchHint(
    motion: ComposerHintMotion, searching: Boolean, draftEmpty: Boolean, queryEmpty: Boolean,
    draftStart: Dp, searchIconGap: Dp, draftEnd: Dp, draftTop: Dp, minHeight: Dp, style: TextStyle,
): Modifier {
    // The active search field draws its own stationary icon, including when its query contains text.
    if ((searching && !queryEmpty) || (!searching && !draftEmpty)) return this
    val searchHint = stringResource(R.string.chat_picker_search_hint)
    val searchColor = MaterialTheme.colorScheme.onSurfaceVariant
    val measurer = rememberTextMeasurer()
    val magnifier = rememberVectorPainter(Icons.Outlined.Search)
    return drawWithCache {
        val width = size.width.roundToInt()
        val start = draftStart.roundToPx()
        val iconStart = OpenTextStart.roundToPx()
        val iconSize = SearchIconSize.roundToPx()
        val searchStart = iconStart + iconSize + searchIconGap.roundToPx()
        fun measure(text: String, space: Int) = measurer.measure(text, style,
            maxLines = 1, overflow = TextOverflow.Ellipsis, constraints = Constraints(maxWidth = space.coerceAtLeast(0)))
        val draftLine = measure(ChatDraftHint, width - start - draftEnd.roundToPx())
        val searchLine = measure(searchHint, width - searchStart - OpenTextEnd.roundToPx())
        val rowHeight = minOf(minHeight.roundToPx(), size.height.roundToInt())
        val bottom = TextBottom.roundToPx()
        val searchTop = OpenTextTop.roundToPx()
        val fromBaseline = modelHintTop(rowHeight, draftTop.roundToPx(), bottom, draftLine.size.height) + draftLine.firstBaseline
        val toBaseline = modelHintTop(rowHeight, searchTop, bottom, searchLine.size.height, iconSize) + searchLine.firstBaseline
        val rtl = layoutDirection == LayoutDirection.Rtl
        val iconX = if (rtl) width - iconStart - iconSize else iconStart
        val iconY = modelHintTop(rowHeight, searchTop, bottom, iconSize, searchLine.size.height)
        val iconTint = ColorFilter.tint(searchColor)
        onDrawBehind {
            val p = motion.search
            val shift = modelHintOffsetX(start, searchStart, 0, p, rtl)
            val baseline = fromBaseline + (toBaseline - fromBaseline) * p
            fun origin(line: TextLayoutResult) = Offset(if (rtl) size.width - line.size.width + shift else shift,
                baseline - line.firstBaseline)
            clipRect {
                if (draftEmpty && p < 1f) drawText(draftLine, HubStyle.ComposerHint, origin(draftLine), alpha = 1f - p)
                if (queryEmpty && p > 0f) drawText(searchLine, searchColor, origin(searchLine), alpha = p)
                // On exit the search editor gives up its placement immediately. Keep only its decorative icon.
                if (!searching && p > 0f) translate(iconX.toFloat(), iconY.toFloat()) {
                    with(magnifier) { draw(Size(iconSize.toFloat(), iconSize.toFloat()), alpha = p, colorFilter = iconTint) }
                }
            }
        }
    }
}
