package io.hydrion.hub.ui.composer

internal sealed interface ComposerMorph {
    enum class Tween(val end: Float, val millis: Int) : ComposerMorph {
        Open(1f, 48),
        LoseFocus(0f, 33),
    }

    data class At(val fraction: Float) : ComposerMorph
}

/** The host follows the physical IME. Only dismissal couples the composer's shape to its height. */
internal class ComposerMotion {
    private data class Dismissal(val startHeight: Int, val startFraction: Float) {
        fun at(height: Int) = startFraction * (2f * height / startHeight - 1f).coerceIn(0f, 1f)
    }

    private var lastHeight = 0
    private var dismissal: Dismissal? = null

    fun update(
        height: Int, source: Int, target: Int, editing: Boolean, hardwareOnly: Boolean,
        renderedFraction: Float,
    ): ComposerMorph {
        val previous = lastHeight
        // A target may precede all travel, or briefly outlive a missing current inset mid-opening.
        if (height == 0 && target > 0 && previous > 0 && dismissal == null && editing) {
            return ComposerMorph.Tween.Open
        }
        lastHeight = height
        if (height == 0) {
            dismissal = null
            return when {
                !hardwareOnly -> ComposerMorph.At(0f)
                editing -> ComposerMorph.Tween.Open
                else -> ComposerMorph.Tween.LoseFocus
            }
        }
        if (!editing) {
            dismissal = null
            return ComposerMorph.Tween.LoseFocus
        }

        val hiding = source > 0 && target == 0
        if (hiding && dismissal == null) {
            // Reversals start where we actually drew the fast opening, not at the IME's travel ratio.
            val start = if (previous > 0) previous else maxOf(source, height)
            dismissal = Dismissal(start, renderedFraction)
        } else if (!hiding && (source != target || height > previous)) {
            // Also accept a real rise when the keyboard omits the reversal's animation bounds.
            dismissal = null
        }
        // During a close, identical/missing bounds alone do not change direction.
        return dismissal?.let { ComposerMorph.At(it.at(height)) } ?: ComposerMorph.Tween.Open
    }
}
