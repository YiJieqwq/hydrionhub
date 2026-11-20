package io.hydrion.hub.provider

import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatModelPickerLayoutTest {
    @Test fun cardEndsAtItsTriggerAndMakesRoomForTheKeyboard() {
        val anchor = IntRect(210, 270, 344, 318)
        val result = pickerSpace(anchor, IntSize(410, 340), 8, 244, 112, LayoutDirection.Ltr)
        assertEquals(100, result.left)
        assertEquals(262, result.edgeY)
        assertEquals(254, result.maxHeight)
        assertTrue(result.above)
    }

    @Test fun narrowWindowClampsBothSidesAndBoundsTheScrollableList() {
        val anchor = IntRect(90, 150, 165, 198)
        val result = pickerSpace(anchor, IntSize(172, 204), 8, 244, 112, LayoutDirection.Ltr)
        assertEquals(8, result.left)
        assertEquals(156, result.width)
        assertEquals(134, result.maxHeight)
        assertTrue(result.edgeY < anchor.top)
    }

    @Test fun prefersAvailableSpaceBelowNearTheTopAndMirrorsItsAnchorInRtl() {
        val anchor = IntRect(31, 20, 145, 68)
        val result = pickerSpace(anchor, IntSize(410, 260), 8, 244, 112, LayoutDirection.Rtl)
        assertEquals(31, result.left)
        assertEquals(false, result.above)
        assertEquals(76, result.edgeY)
        assertEquals(176, result.maxHeight)
    }
}
