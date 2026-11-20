package io.hydrion.hub.ui.composer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolsToolbarMorphTest {
    @Test fun endpointsSwapTheirActualPhysicalSlotsAndKeepAbsoluteRotationSigns() {
        for (rtl in listOf(false, true)) {
            val start = ToolsToolbarPose(252f, 42f, 0f, rtl)
            val end = ToolsToolbarPose(252f, 42f, 1f, rtl)
            assertEquals(if (rtl) 210f else 0f, start.attachmentX, 0f)
            assertEquals(start.attachmentX, end.sendX, 0f)
            assertEquals(start.sendX, end.attachmentX, 0f)
            assertEquals(45f, end.plusDegrees, 0f)
            assertEquals(-90f, end.arrowDegrees, 0f)
            for (step in 0..20) {
                val p = step / 20f
                val pose = ToolsToolbarPose(252f, 42f, p, rtl)
                val reverse = ToolsToolbarPose(252f, 42f, 1f - p, rtl)
                assertEquals(p * 45, pose.plusDegrees, 0f)
                assertEquals(-p * 90, pose.arrowDegrees, 0f)
                assertEquals(pose.attachmentX, reverse.sendX, .0001f)
                assertEquals(pose.sendX, reverse.attachmentX, .0001f)
                assertTrue(pose.moveToCenter(64f, true) * (if (rtl) -1f else 1f) >= 0f)
                assertEquals(-pose.moveToCenter(64f, true), pose.moveToCenter(64f, false), 0f)
            }
        }
    }

    @Test fun narrowRailNeverPushesEndpointsOutsideOrOverlapsSettledActions() {
        for (width in listOf(0f, 19f, 41f, 83f, 84f, 143f)) for (rtl in listOf(false, true)) {
            for (step in 0..20) {
                val p = step / 20f
                val pose = ToolsToolbarPose(width, 42f, p, rtl)
                for (left in listOf(pose.sendX, pose.attachmentX)) {
                    assertTrue(left >= 0 && left + pose.slotWidth <= width + .0001f)
                }
                if (step == 0 || step == 20)
                    assertTrue(kotlin.math.abs(pose.sendX - pose.attachmentX) >= pose.slotWidth)
            }
        }
    }

    @Test fun onlySettledTargetAcceptsActionsDuringOpenCloseAndReversal() {
        fun actions(p: Float, open: Boolean) = ToolsToolbarPose(200f, 42f, p, false).actions(open)
        assertEquals(ToolsToolbarActions.Composer, actions(0f, false))
        assertEquals(ToolsToolbarActions.Moving, actions(0f, true))
        for (p in listOf(.01f, .5f, .99f)) for (open in listOf(false, true))
            assertEquals(ToolsToolbarActions.Moving, actions(p, open))
        assertEquals(ToolsToolbarActions.Tools, actions(1f, true))
        assertEquals(ToolsToolbarActions.Moving, actions(1f, false))
    }
}
