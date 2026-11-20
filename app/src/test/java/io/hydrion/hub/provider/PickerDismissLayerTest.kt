package io.hydrion.hub.provider

import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import org.junit.Assert.assertEquals
import org.junit.Test

class PickerDismissLayerTest {
    @Test fun everyPixelOutsideTheClippedFieldBelongsToExactlyOneDismissRegion() {
        val viewport = IntSize(41, 73)
        val fields = listOf(null, IntRect.Zero, IntRect(3, 40, 38, 55), IntRect(-6, 30, 35, 92),
            IntRect(29, -5, 61, 16), IntRect(0, 0, 41, 73), IntRect(9, 85, 30, 120))
        for (field in fields) {
            val pieces = outsideEditor(viewport, field)
            for (y in 0 until viewport.height) for (x in 0 until viewport.width) {
                val p = IntOffset(x, y)
                assertEquals("field=$field point=$p", if (field?.contains(p) == true) 0 else 1,
                    pieces.count { it.contains(p) })
            }
        }
    }
}
