package io.hydrion.hub.ui.composer

import io.hydrion.hub.ui.ChatPanelMotionMillis
import kotlin.math.abs
import kotlin.math.roundToInt

internal enum class ComposerRowTarget(val fraction: Float) {
    Folded(0f), Browsing(0f), Content(1f), ParkedSearch(1f), HeldFolded(0f);

    fun travelMillisFrom(previous: ComposerRowTarget, displayed: Float): Int {
        if (this == HeldFolded || displayed == fraction) return 0
        val searchChange = (this == ParkedSearch && (previous == Browsing || previous == Folded)) ||
            (this == Browsing && previous == ParkedSearch)
        return if (searchChange) (ChatPanelMotionMillis * abs(fraction - displayed)).roundToInt() else 33
    }
}

internal fun composerRowTarget(
    heldFolded: Boolean, hasDraftOrAttachment: Boolean, searching: Boolean, modelList: Boolean, imeActive: Boolean,
): ComposerRowTarget = when {
    heldFolded -> ComposerRowTarget.HeldFolded
    hasDraftOrAttachment -> ComposerRowTarget.Content
    searching && !imeActive -> ComposerRowTarget.ParkedSearch
    searching -> ComposerRowTarget.Content
    modelList && !imeActive -> ComposerRowTarget.Browsing
    else -> ComposerRowTarget.Folded
}
