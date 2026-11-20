package io.hydrion.hub.provider

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Spacer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.ViewConfiguration
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize

/** Leaves the visible editor out of hit testing so it receives the original gesture. */
internal fun outsideEditor(viewport: IntSize, field: IntRect?): List<IntRect> {
    val w = viewport.width
    val h = viewport.height
    val full = IntRect(0, 0, w, h)
    val hole = field?.intersect(full)
    if (hole == null || hole.isEmpty) return listOf(full, IntRect.Zero, IntRect.Zero, IntRect.Zero)
    return listOf(
        IntRect(0, 0, w, hole.top),
        IntRect(0, hole.bottom, w, h),
        IntRect(0, hole.top, hole.left, hole.bottom),
        IntRect(hole.right, hole.top, w, hole.bottom),
    )
}

@Composable
internal fun PickerDismissLayer(
    picker: ChatModelPickerState, label: String, modifier: Modifier, fieldBounds: () -> IntRect?,
) {
    // These are hit regions around an editor, not individual buttons. Android's expanded touch
    // targets for narrow strips must not spill back into the field while the IME is moving.
    val config = LocalViewConfiguration.current
    val exactBounds = remember(config) {
        object : ViewConfiguration by config { override val minimumTouchTargetSize = DpSize.Zero }
    }
    CompositionLocalProvider(LocalViewConfiguration provides exactBounds) {
        Layout(modifier = modifier, content = {
            repeat(4) { i ->
                val accessible = if (i == 0) Modifier.semantics {
                    role = Role.Button
                    contentDescription = label
                    onClick { picker.dismiss(); true }
                } else Modifier
                Spacer(accessible.pointerInput(picker) { detectTapGestures(onTap = { picker.dismiss() }) })
            }
        }) { children, limits ->
            val rects = outsideEditor(IntSize(limits.maxWidth, limits.maxHeight), fieldBounds())
            val pieces = children.mapIndexed { i, child ->
                child.measure(Constraints.fixed(rects[i].width, rects[i].height))
            }
            layout(limits.maxWidth, limits.maxHeight) {
                pieces.forEachIndexed { i, piece ->
                    val rect = rects[i]
                    if (!rect.isEmpty) piece.place(rect.left, rect.top)
                }
            }
        }
    }
}
