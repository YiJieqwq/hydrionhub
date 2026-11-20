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

/** Each rectangular control can divide a dismissal region into at most four disjoint strips. */
internal fun pickerDismissRegions(viewport: IntSize, field: IntRect?, trigger: IntRect?): List<IntRect> {
    fun IntRect.excluding(target: IntRect?): List<IntRect> {
        val cut = target?.intersect(this)
        if (cut == null || cut.isEmpty) return listOf(this)
        return listOf(
            IntRect(left, top, right, cut.top),
            IntRect(left, cut.bottom, right, bottom),
            IntRect(left, cut.top, cut.left, cut.bottom),
            IntRect(cut.right, cut.top, right, cut.bottom),
        ).filterNot { it.isEmpty }
    }
    val full = IntRect(0, 0, viewport.width, viewport.height)
    return full.excluding(field).flatMap { it.excluding(trigger) }
}

@Composable
internal fun PickerDismissLayer(
    picker: ChatModelPickerState, label: String, modifier: Modifier, fieldBounds: () -> IntRect?, triggerBounds: () -> IntRect?,
) {
    // These are hit regions around an editor, not individual buttons. Android's expanded touch
    // targets for narrow strips must not spill back into the field while the IME is moving.
    val config = LocalViewConfiguration.current
    val exactBounds = remember(config) {
        object : ViewConfiguration by config { override val minimumTouchTargetSize = DpSize.Zero }
    }
    CompositionLocalProvider(LocalViewConfiguration provides exactBounds) {
        Layout(modifier = modifier, content = {
            // Precompose the upper bound so IME motion only remeasures and moves the active strips.
            repeat(4 * 4) { i ->
                val accessible = if (i == 0) Modifier.semantics {
                    role = Role.Button
                    contentDescription = label
                    onClick { picker.dismiss(); true }
                } else Modifier
                Spacer(accessible.pointerInput(picker) { detectTapGestures(onTap = { picker.dismiss() }) })
            }
        }) { children, limits ->
            val rects = pickerDismissRegions(IntSize(limits.maxWidth, limits.maxHeight), fieldBounds(), triggerBounds())
            val pieces = rects.mapIndexed { i, rect ->
                children[i].measure(Constraints.fixed(rect.width, rect.height))
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
