package io.hydrion.hub.ui.composer

import kotlin.math.roundToInt

/** Starts are distances from the capsule's logical start. Layer translations are physical pixels. */
internal fun modelHintOffsetX(
    draftRestStart: Int, searchStart: Int, nativeStart: Int, progress: Float, rtl: Boolean,
): Float {
    val fraction = progress.coerceIn(0f, 1f)
    val logicalStart = draftRestStart + (searchStart - draftRestStart) * fraction
    val displacement = logicalStart - nativeStart
    return if (rtl) -displacement else displacement
}

/** Mirrors heightIn(min), vertical padding, then CenterVertically for the measured line. */
internal fun modelHintTop(
    minHeight: Int, topPadding: Int, bottomPadding: Int, lineHeight: Int, minContentHeight: Int = 0,
): Int {
    val available = maxOf(minHeight - topPadding - bottomPadding, minContentHeight, lineHeight)
    return topPadding + ((available - lineHeight) / 2f).roundToInt()
}
