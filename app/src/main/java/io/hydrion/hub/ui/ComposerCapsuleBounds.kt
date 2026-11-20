package io.hydrion.hub.ui

import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.AlignmentLine
import androidx.compose.ui.layout.HorizontalAlignmentLine
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.layout.VerticalAlignmentLine
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.LayoutDirection

// Published by the measured capsule and propagated through its actual padding, insets and error bars.
private val CapsuleLeft = VerticalAlignmentLine(::minOf)
private val CapsuleRight = VerticalAlignmentLine(::maxOf)
private val CapsuleTop = HorizontalAlignmentLine(::minOf)
private val CapsuleBottom = HorizontalAlignmentLine(::maxOf)

internal fun Modifier.pickerCapsuleBounds() = layout { measurable, constraints ->
    val capsule = measurable.measure(constraints)
    layout(capsule.width, capsule.height, mapOf(CapsuleLeft to 0, CapsuleTop to 0,
        CapsuleRight to capsule.width, CapsuleBottom to capsule.height)) { capsule.place(0, 0) }
}

internal fun Placeable.capsuleBounds(): IntRect? {
    val top = this[CapsuleTop]
    if (top == AlignmentLine.Unspecified) return null
    return IntRect(this[CapsuleLeft], top, this[CapsuleRight], this[CapsuleBottom])
}

// Relative to the picker's safe viewport. Only its measure policy consumes this value.
internal val LocalPickerComposerBounds = compositionLocalOf<IntRect?> { null }

private val ActionEnd = VerticalAlignmentLine(::minOf)
private val ActionBottom = HorizontalAlignmentLine(::maxOf)

// Publish the painted disk's edges after its visual offset, inside the larger touch slot.
internal fun Modifier.composerActionFace() = layout { measurable, constraints ->
    val face = measurable.measure(constraints)
    val end = if (layoutDirection == LayoutDirection.Ltr) face.width else 0
    layout(face.width, face.height, mapOf(ActionEnd to end, ActionBottom to face.height)) { face.place(0, 0) }
}

internal fun Placeable.actionInsets(capsule: IntRect, direction: LayoutDirection): IntOffset? {
    val end = this[ActionEnd]
    val bottom = this[ActionBottom]
    if (end == AlignmentLine.Unspecified || bottom == AlignmentLine.Unspecified) return null
    return IntOffset(if (direction == LayoutDirection.Ltr) capsule.right - end else end - capsule.left,
        capsule.bottom - bottom)
}

// Logical end and bottom distances from the send disk to its capsule, in this same measure pass.
internal val LocalComposerActionInsets = compositionLocalOf<IntOffset?> { null }
