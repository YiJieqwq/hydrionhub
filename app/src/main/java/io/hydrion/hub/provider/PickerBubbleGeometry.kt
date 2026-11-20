package io.hydrion.hub.provider

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.toRect

internal enum class PickerBubbleFace(val weights: Offset) {
    Label(Offset.Zero),
    Effort(Offset(1f, 0f)),
    List(Offset(0f, 1f)),
}

/**
 * The same two coordinates describe every leg, including a direct list-to-label exit.
 * Retargeting from the current weights preserves all four edges at the interruption frame.
 */
internal fun pickerBubbleBounds(label: Rect, effort: Rect, list: Rect, weights: Offset): Rect {
    val effortWeight = weights.x.coerceIn(0f, 1f)
    val listWeight = weights.y.coerceIn(0f, 1f - effortWeight)
    val labelWeight = 1f - effortWeight - listWeight
    // Relative to the effort edge, the common right/bottom edges stay exactly fixed between panels.
    fun edge(source: Float, card: Float, menu: Float) =
        card + (source - card) * labelWeight + (menu - card) * listWeight
    return Rect(
        edge(label.left, effort.left, list.left),
        edge(label.top, effort.top, list.top),
        edge(label.right, effort.right, list.right),
        edge(label.bottom, effort.bottom, list.bottom),
    )
}

/** Arguments use viewport coordinates. A missing or entirely clipped label starts as a point. */
internal fun pickerBubbleLabel(measured: IntRect?, trigger: IntRect?, composer: IntRect, viewport: IntSize): Rect {
    val screen = Rect(0f, 0f, viewport.width.toFloat(), viewport.height.toFloat())
    val bar = composer.toRect().intersect(screen)
    val label = measured?.toRect()?.intersect(bar)
    if (label != null && !label.isEmpty) return label

    val button = trigger?.toRect()?.intersect(bar)
    val point = if (button != null && !button.isEmpty) button.center else Offset(
        (composer.center.x + composer.width * 0.25f).coerceIn(0f, screen.right),
        composer.bottom.toFloat().coerceIn(0f, screen.bottom),
    )
    return Rect(point, point)
}
