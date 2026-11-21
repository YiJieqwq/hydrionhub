package io.hydrion.hub.ui.composer

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EditorDoubleTapTest {
    private val original = TextFieldValue("one two", selection = TextRange(4, 7))
    private val afterTap = original.copy(selection = TextRange(1))
    private val origin = Offset(20f, 30f)
    private fun sequence() = EditorTapSequence(40, 300, 500, 8f, 32f)

    private fun EditorTapSequence.firstTap() {
        begin(100, origin, original)
        assertNull(end(120, origin))
    }

    @Test fun effortTapCompletesOnFirstReleaseButNotAfterDragLongPressOrCancel() {
        val taps = sequence()
        taps.begin(100, origin, original)
        assertTrue(taps.endSingle(120, origin))
        assertFalse(taps.endSingle(121, origin))
        taps.begin(200, origin, original)
        taps.move(210, origin + Offset(15f, 0f))
        assertFalse(taps.endSingle(220, origin))
        taps.begin(300, origin, original)
        assertFalse(taps.endSingle(800, origin))
        taps.begin(900, origin, original)
        taps.cancel()
        assertFalse(taps.endSingle(920, origin))
    }

    @Test fun onlyCompletedSecondTapReturnsFirstSelectionAndCannotFireAgainOnThirdTap() {
        val taps = sequence()
        taps.firstTap()
        taps.begin(200, origin + Offset(12f, 0f), afterTap)
        assertEquals(original, taps.end(235, origin + Offset(13f, 0f)))
        taps.begin(300, origin, afterTap)
        assertNull(taps.end(325, origin))
    }

    @Test fun pairingUsesPlatformIntervalFromFirstUpToSecondDown() {
        for (gap in listOf(39L, 40L, 300L, 301L)) {
            val taps = sequence()
            taps.firstTap()
            taps.begin(120 + gap, origin, afterTap)
            assertEquals(if (gap in 40..300) original else null, taps.end(140 + gap, origin))
        }
    }

    @Test fun distantSecondTapAfterWindowMovesIsANewFirstTap() {
        val taps = sequence()
        taps.firstTap()
        val newWindowPoint = origin + Offset(0f, -140f)
        taps.begin(180, newWindowPoint, afterTap)
        assertNull(taps.end(205, newWindowPoint))
        taps.begin(270, newWindowPoint, afterTap)
        assertEquals(afterTap, taps.end(300, newWindowPoint))
    }

    @Test fun cancelBetweenDownAndUpInvalidatesBothTaps() {
        val taps = sequence()
        taps.firstTap()
        taps.begin(200, origin, afterTap)
        taps.cancel()
        assertNull(taps.end(240, origin))
        taps.begin(280, origin, afterTap)
        assertNull(taps.end(300, origin))
    }

    @Test fun draggingOutAndBackOrHoldingEitherTapCannotDismiss() {
        for (rejectFirst in listOf(true, false)) {
            for (longPress in listOf(true, false)) {
                val taps = sequence()
                if (!rejectFirst) taps.firstTap()
                val down = if (rejectFirst) 100L else 200L
                taps.begin(down, origin, original)
                if (!longPress) taps.move(down + 10, origin + Offset(6f, 6f))
                val up = down + if (longPress) 500 else 20
                assertNull(taps.end(up, origin))
                taps.begin(up + 60, origin, afterTap)
                assertNull(taps.end(up + 90, origin))
            }
        }
    }

    @Test fun editingBetweenTapsNeverRestoresAnOlderDraft() {
        val taps = sequence()
        taps.firstTap()
        val edited = TextFieldValue("updated")
        taps.begin(210, origin, edited)
        assertNull(taps.end(245, origin))
        taps.begin(340, origin, edited)
        assertEquals(edited, taps.end(365, origin))
    }
}
