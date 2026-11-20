package io.hydrion.hub.ui

internal class SlidingPageBackdrop(private val heightPx: Float, nominalFeatherPx: Float) {
    val featherPx = minOf(nominalFeatherPx, heightPx * .25f)

    // The leading edge starts above the viewport and finishes below it. At rest the
    // entire viewport has one material opacity, including its last visible pixel.
    fun solidBottomPx(progress: Float) = (heightPx + featherPx) * progress.coerceIn(0f, 1f) - featherPx

    companion object {
        const val BodyOpacity = .82f
    }
}
