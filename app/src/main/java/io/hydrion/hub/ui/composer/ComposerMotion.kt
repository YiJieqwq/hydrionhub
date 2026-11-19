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

internal data class ComposerImeFrame(val height: Int, val fraction: Float, val leg: ComposerImeLeg?)

internal fun nextComposerImeFrame(
    previous: ComposerImeFrame?, height: Int, source: Int, target: Int,
    expandWithoutIme: Boolean = false,
): ComposerImeFrame {
    val active = previous?.leg
    if (source == target) {
        // Some keyboards briefly stop reporting animation bounds while the inset is still travelling.
        if (active != null && height != target) return ComposerImeFrame(height, active.at(height), active)
        val fraction = if (height > 0 || expandWithoutIme) 1f else 0f
        return ComposerImeFrame(height, fraction, null)
    }
    val leg = when {
        active?.destination == target -> active
        // Bounds may arrive after focus or even after an inset jump. Start at the last rendered frame.
        previous != null -> ComposerImeLeg(previous.height, target, previous.fraction)
        else -> ComposerImeLeg(source, target, if (source > 0 || expandWithoutIme) 1f else 0f)
    }
    return ComposerImeFrame(height, leg.at(height), leg)
}
