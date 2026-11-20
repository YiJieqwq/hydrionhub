package io.hydrion.hub.ui.composer

import kotlin.math.abs

/** Measured composer and IME state for one frame of the return from model configuration. */
internal data class ModelEditorReturnFrame(
    val width: Int,
    val height: Int,
    val imeTravel: Int,
    val imeSource: Int,
    val imeTarget: Int,
    val imeVisible: Boolean,
    val expansion: Float,
    val rowGate: Float,
    val hasRowContent: Boolean,
    val forcedFolded: Boolean,
) {
    fun progress(expectIme: Boolean, entryImeTravel: Int): Float {
        if (forcedFolded || width <= 0 || height <= 0) return 0f
        // A floating keyboard leaves the composer parked even though the IME is visible.
        val floating = expectIme && entryImeTravel == 0 && imeTravel == 0 && imeSource == 0 && imeTarget == 0
        val shape = if (expectIme && !floating) expansion else 1f - expansion
        val row = if (hasRowContent) rowGate else 1f - rowGate
        val inset = when {
            !expectIme -> {
                val start = maxOf(imeTravel, imeSource, entryImeTravel)
                when {
                    imeTarget > imeSource -> 0f
                    imeTravel > 0 -> 1f - imeTravel.toFloat() / start
                    imeVisible || imeTarget > 0 -> 0f
                    else -> 1f
                }
            }
            floating -> if (imeVisible) 1f else 0f
            imeSource != imeTarget -> {
                if (imeTarget <= 0) 0f
                else 1f - abs(imeTarget - imeTravel).toFloat() / abs(imeTarget - imeSource)
            }
            else -> {
                // Legacy animation bounds can be absent or both mirror the moving current inset.
                val target = maxOf(entryImeTravel, imeTarget)
                if (target > 0) imeTravel.toFloat() / target else if (imeTravel > 0) 1f else 0f
            }
        }
        return minOf(inset, shape, row).coerceIn(0f, 1f)
    }

    fun atDestination(expectIme: Boolean, entryImeTravel: Int): Boolean {
        if (imeVisible != expectIme) return false
        // Legacy bounds may mirror the current inset, and the restored IME may change size.
        // The caller requires these measured candidates to remain unchanged across frames.
        val unbounded = expectIme && imeTravel > 0 && imeSource == imeTarget &&
            (imeTarget == 0 || imeTarget == imeTravel)
        return progress(expectIme, if (unbounded) imeTravel else entryImeTravel) == 1f
    }
}
