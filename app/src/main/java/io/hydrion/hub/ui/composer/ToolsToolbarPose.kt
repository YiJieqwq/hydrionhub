package io.hydrion.hub.ui.composer

internal enum class ToolsToolbarActions { Composer, Moving, Tools }

/** Physical coordinates. RTL swaps the slots, never the clockwise/counterclockwise rotation. */
internal class ToolsToolbarPose(width: Float, preferredSlot: Float, progress: Float, private val rtl: Boolean) {
    val fraction = progress.coerceIn(0f, 1f)
    val slotWidth = minOf(preferredSlot, kotlin.math.floor(width / 2))
    private val travel = width - slotWidth
    private val middleWidth = width - 2 * slotWidth
    val attachmentX = travel * if (rtl) 1f - fraction else fraction
    val sendX = travel - attachmentX
    val plusDegrees = 45f * fraction
    val arrowDegrees = -90f * fraction
    // Let the controls start gathering before the fade, and replay the same path on return.
    val middleAlpha = ((1f - fraction) / .65f).coerceIn(0f, 1f)

    fun moveToCenter(groupWidth: Float, leading: Boolean): Float {
        val distance = ((middleWidth - groupWidth) / 2).coerceAtLeast(0f)
        return if (leading != rtl) distance * fraction else -distance * fraction
    }

    fun actions(toolsOpen: Boolean): ToolsToolbarActions = when {
        !toolsOpen && fraction == 0f -> ToolsToolbarActions.Composer
        toolsOpen && fraction == 1f -> ToolsToolbarActions.Tools
        else -> ToolsToolbarActions.Moving
    }
}
