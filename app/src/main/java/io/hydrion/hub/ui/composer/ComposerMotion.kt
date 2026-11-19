package io.hydrion.hub.ui.composer

/** One direction of travel. A new destination begins at the last rendered height and fraction. */
internal data class ComposerImeLeg(
    val start: Int,
    val destination: Int,
    val startFraction: Float,
) {
    fun at(height: Int): Float {
        val closing = destination == 0
        val endFraction = if (closing) 0f else 1f
        val distance = destination - start
        if (distance == 0) return endFraction
        // Leave the second half of dismissal to Android: the collapsed capsule just follows the IME.
        val travel = if (closing) distance / 2f else distance.toFloat()
        val fraction = ((height - start) / travel).coerceIn(0f, 1f)
        return startFraction + (endFraction - startFraction) * fraction
    }
}

internal data class ComposerImeFrame(val height: Int, val leg: ComposerImeLeg?) {
    val fraction: Float? get() = leg?.at(height)
}

internal fun nextComposerImeFrame(previous: ComposerImeFrame?, height: Int, source: Int, target: Int): ComposerImeFrame {
    if (source == target) return ComposerImeFrame(height, null)
    val active = previous?.leg
    val leg = when {
        active == null -> ComposerImeLeg(source, target, if (source > 0) 1f else 0f)
        active.destination == target -> active
        previous.height != active.destination ->
            ComposerImeLeg(previous.height, target, active.at(previous.height))
        else -> ComposerImeLeg(source, target, active.at(previous.height))
    }
    return ComposerImeFrame(height, leg)
}
