package io.hydrion.hub.provider

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.toRect

internal enum class PickerBubbleFace(val weights: Offset) {
    Label(Offset.Zero),
    Effort(Offset(1f, 0f)),
    List(Offset(0f, 1f)), // Capability options only; browsing models uses the full page.
}

/**
 * The same two coordinates describe every leg, including a direct list-to-label exit.
 * Retargeting from the current weights preserves all four edges at the interruption frame.
 */
internal fun pickerBubbleBounds(label: Rect, effort: Rect, list: Rect, weights: Offset): Rect {
    val effortWeight = weights.x.coerceIn(0f, 1f)
    val listWeight = weights.y.coerceIn(0f, 1f - effortWeight)
    val labelWeight = 1f - effortWeight - listWeight
    // Any edge common to the two destinations stays fixed throughout their morph.
    fun edge(source: Float, card: Float, menu: Float) =
        card + (source - card) * labelWeight + (menu - card) * listWeight
    return Rect(
        edge(label.left, effort.left, list.left),
        edge(label.top, effort.top, list.top),
        edge(label.right, effort.right, list.right),
        edge(label.bottom, effort.bottom, list.bottom),
    )
}

/** One visual leg driven by the tools page's progress, including reversals partway through it. */
internal data class BrowseEffortLeg(
    val start: Float, val end: Float, val startCoverage: Float, val endCoverage: Float,
    val startAlpha: Float, val endAlpha: Float,
) {
    private fun fraction(progress: Float) = if (start == end) 1f else ((progress - start) / (end - start)).coerceIn(0f, 1f)
    fun coverage(progress: Float): Float = startCoverage + (endCoverage - startCoverage) * fraction(progress)
    fun alpha(progress: Float): Float = startAlpha + (endAlpha - startAlpha) * fraction(progress)
}

/** Both endpoints are measured before toolbar travel. Translate the complete interpolated rectangle once. */
internal fun translatedPickerBounds(label: Rect, effort: Rect, options: Rect, weights: Offset, travelX: Int): Rect =
    pickerBubbleBounds(label, effort, options, weights).translate(travelX.toFloat(), 0f)

/** Arguments use viewport coordinates. A missing or entirely clipped label starts as a point. */
internal fun pickerBubbleLabel(
    measured: IntRect?, trigger: IntRect?, composer: IntRect, viewport: IntSize,
    direction: LayoutDirection = LayoutDirection.Ltr,
): Rect {
    val screen = Rect(0f, 0f, viewport.width.toFloat(), viewport.height.toFloat())
    val bar = composer.toRect().intersect(screen)
    val label = measured?.toRect()?.intersect(bar)
    if (label != null && !label.isEmpty) return label

    val button = trigger?.toRect()?.intersect(bar)
    val point = if (button != null && !button.isEmpty) button.center else Offset(
        (composer.center.x + composer.width * (if (direction == LayoutDirection.Ltr) .25f else -.25f)).coerceIn(0f, screen.right),
        composer.bottom.toFloat().coerceIn(0f, screen.bottom),
    )
    return Rect(point, point)
}
