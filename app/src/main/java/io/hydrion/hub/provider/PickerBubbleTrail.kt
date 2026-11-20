package io.hydrion.hub.provider

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.sign

private const val MaxTrailDp = 2f

// Weights are measured in units per real second. Each side can move independently:
// for instance the top and left move in opposite directions on the way to the list.
internal fun pickerEdgeTrailPx(label: Float, effort: Float, list: Float, velocity: Offset, density: Float): Float {
    val pxPerSecond = (effort - label) * velocity.x + (list - label) * velocity.y
    val strength = ((abs(pxPerSecond) / density - 350f) / 650f).coerceIn(0f, 1f)
    return -sign(pxPerSecond) * strength * MaxTrailDp * density
}

internal fun DrawScope.drawPickerEdgeTrail(
    rect: Rect, label: Rect, effort: Rect, list: Rect, velocity: Offset,
    radius: Float, color: Color, path: Path,
) {
    fun lag(s: Float, e: Float, l: Float) = pickerEdgeTrailPx(s, e, l, velocity, density)
    val dxLeft = lag(label.left, effort.left, list.left)
    val dyTop = lag(label.top, effort.top, list.top)
    val dxRight = lag(label.right, effort.right, list.right)
    val dyBottom = lag(label.bottom, effort.bottom, list.bottom)
    if (dxLeft == 0f && dyTop == 0f && dxRight == 0f && dyBottom == 0f) return
    val echo = Rect(rect.left + dxLeft, rect.top + dyTop, rect.right + dxRight, rect.bottom + dyBottom)
    if (echo.minDimension < 2.dp.toPx()) return
    val diameter = (radius * 2f).coerceAtMost(echo.minDimension)
    val tl = Rect(echo.left, echo.top, echo.left + diameter, echo.top + diameter)
    val tr = tl.translate(echo.width - diameter, 0f)
    val bl = tl.translate(0f, echo.height - diameter)
    val br = tr.translate(0f, echo.height - diameter)
    val stroke = Stroke(width = 0.8.dp.toPx())
    fun side(offset: Float, from: Rect, to: Rect, angle: Float) {
        if (offset == 0f) return
        path.reset()
        // The two half-corners join at a straight tangent. Stationary sides get no extra ink.
        path.arcTo(from, angle, 45f, true)
        path.arcTo(to, angle + 45f, 45f, false)
        drawPath(path, color.copy(alpha = .075f * abs(offset) / (MaxTrailDp * density)), style = stroke)
    }
    side(dyTop, tl, tr, 225f)
    side(dxRight, tr, br, -45f)
    side(dyBottom, br, bl, 45f)
    side(dxLeft, bl, tl, 135f)
}
