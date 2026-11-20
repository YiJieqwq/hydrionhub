package io.hydrion.hub.ui.composer

internal sealed interface ComposerMorph {
    enum class Tween(val end: Float, val millis: Int) : ComposerMorph {
        Open(1f, 48),
        LoseFocus(0f, 33),
    }

    data class At(val fraction: Float) : ComposerMorph

    // A feature can release editor focus well before Android moves the IME, or transfer focus
    // to another editor that keeps the same IME indefinitely.
    data class FollowAfterBlur(val fraction: Float) : ComposerMorph {
        companion object { const val IdleTimeoutMillis = 600L }
    }
}

/** The host follows the physical IME. Only dismissal couples the composer's shape to its height. */
internal class ComposerMotion {
    private data class Dismissal(val startHeight: Int, val startFraction: Float) {
        fun at(height: Int) = startFraction * (2f * height / startHeight - 1f).coerceIn(0f, 1f)
    }

    private var lastHeight = 0
    private var wasEditing = false
    private var dismissal: Dismissal? = null

    fun update(
        height: Int, source: Int, target: Int, editing: Boolean, hardwareOnly: Boolean,
        renderedFraction: Float, imeVisible: Boolean = true,
    ): ComposerMorph {
        val previous = lastHeight
        val regainedFocus = editing && !wasEditing
        wasEditing = editing
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
        val reportedHide = source > 0 && target == 0
        // A still-visible keyboard can resize with no animation bounds (e.g. its suggestion bar).
        val unboundedDrop = !imeVisible && source == target && height < previous
        if (!editing || reportedHide || unboundedDrop) {
            val active = dismissal
            val leg = if (active == null || (!editing && height > previous)) {
                // Stop from the actually visible shape, including when a fast opening was interrupted.
                val start = if (previous > 0) maxOf(previous, height) else maxOf(source, height)
                Dismissal(start, renderedFraction)
            } else active
            dismissal = leg
            if (!editing) {
                // A late inset after the idle fallback must never make the capsule grow again.
                val fraction = minOf(renderedFraction, leg.at(height))
                return if (fraction > 0f) ComposerMorph.FollowAfterBlur(fraction) else ComposerMorph.At(0f)
            }
        } else if (regainedFocus || source != target || height > previous) {
            dismissal = null
        }
        // During a close, identical/missing bounds alone do not change direction.
        return dismissal?.let { ComposerMorph.At(it.at(height)) } ?: ComposerMorph.Tween.Open
    }
}
