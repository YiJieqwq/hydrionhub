package io.hydrion.hub.ui.composer

/**
 * Fraction of the expanded composer at the current IME height, above the navigation bar.
 * The 33 ms edit gate runs concurrently on dismissal; the lower of the inset
 * fraction and edit gate drives the shell geometry.
 */
internal fun composerImeProgress(height: Int, start: Int, end: Int): Float? = when {
    start > 0 && end == 0 -> (height.toFloat() / start).coerceIn(0f, 1f)
    start == 0 && end > 0 -> (height.toFloat() / end).coerceIn(0f, 1f)
    else -> null
}
