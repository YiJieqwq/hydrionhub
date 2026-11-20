package io.hydrion.hub.ui

import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.AlignmentLine
import androidx.compose.ui.layout.HorizontalAlignmentLine
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.layout.VerticalAlignmentLine
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.IntRect

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

// The two alignment lines travel through the same ancestors; their difference is the
// measured height of the single-line search endpoint in any font scale.
private val SearchLineTop = HorizontalAlignmentLine(::minOf)
private val SearchLineBottom = HorizontalAlignmentLine(::maxOf)

internal fun searchLineHeightLines(heightPx: Int): Map<AlignmentLine, Int> =
    mapOf(SearchLineTop to 0, SearchLineBottom to heightPx)

internal fun Placeable.searchDockHeight(): Int {
    val lineTop = this[SearchLineTop]
    val capsuleTop = this[CapsuleTop]
    if (lineTop == AlignmentLine.Unspecified || capsuleTop == AlignmentLine.Unspecified) return height
    val capsuleHeight = this[CapsuleBottom] - capsuleTop
    val lineHeight = (this[SearchLineBottom] - lineTop).coerceIn(0, capsuleHeight)
    // Keep everything outside the changing capsule: error banners, physical bottom gap and insets.
    return height - capsuleHeight + lineHeight
}

// Relative to the picker's safe viewport. Only its measure policy consumes this value.
internal val LocalPickerComposerBounds = compositionLocalOf<IntRect?> { null }

private val LabelLeft = VerticalAlignmentLine(::minOf)
private val LabelRight = VerticalAlignmentLine(::maxOf)
private val LabelTop = HorizontalAlignmentLine(::minOf)
private val LabelBottom = HorizontalAlignmentLine(::maxOf)

internal fun Modifier.pickerModelLabelBounds() = layout { measurable, constraints ->
    val text = measurable.measure(constraints)
    layout(text.width, text.height, mapOf(LabelLeft to 0, LabelTop to 0,
        LabelRight to text.width, LabelBottom to text.height)) { text.place(0, 0) }
}

internal fun Placeable.pickerModelLabel(): IntRect? {
    val top = this[LabelTop]
    if (top == AlignmentLine.Unspecified) return null
    return IntRect(this[LabelLeft], top, this[LabelRight], this[LabelBottom]).takeUnless { it.isEmpty }
}

internal val LocalPickerModelLabelBounds = compositionLocalOf<IntRect?> { null }

private val ModelTravelOrigin = VerticalAlignmentLine(::minOf)
private val ModelTravelDestination = VerticalAlignmentLine(::minOf)
internal val LocalPickerModelTravelX = compositionLocalOf { 0 }

/** Report physical screen direction, independent of layout direction. Ancestor padding cancels in the difference. */
internal fun Modifier.pickerModelTravel(getOffset: (Int) -> Int) = layout { child, constraints ->
    val group = child.measure(constraints)
    val shift = getOffset(group.width)
    layout(group.width, group.height, mapOf(ModelTravelOrigin to 0, ModelTravelDestination to shift)) {
        group.place(shift, 0)
    }
}

internal fun Placeable.pickerModelTravelX(): Int {
    val origin = this[ModelTravelOrigin]
    return if (origin == AlignmentLine.Unspecified) 0 else this[ModelTravelDestination] - origin
}

// At the search endpoint no invisible middle button may intercept the editor. Forward its measured
// geometry without placing the row so a reversal starts from the same label and displacement.
internal fun Modifier.measureOnlyPickerToolbar() = layout { child, constraints ->
    val row = child.measure(constraints)
    val lines = listOf(LabelLeft, LabelRight, LabelTop, LabelBottom, ModelTravelOrigin, ModelTravelDestination)
        .mapNotNull { line -> row[line].takeUnless { it == AlignmentLine.Unspecified }?.let { line to it } }.toMap()
    layout(row.width, row.height, lines) { }
}
