package io.hydrion.hub.ui.settings

import java.time.Instant
import org.junit.Assert.*
import org.junit.Test

class QuotaWindowTest {
    @Test fun unknownUsageDoesNotBecomeZeroOrAFullBar() {
        val window = QuotaWindow(300, null, null, Instant.EPOCH)
        for (value in listOf(null, -1.0, 101.0, Double.NaN, Double.POSITIVE_INFINITY)) {
            assertNull(window.copy(usedPercent = value).validPercent)
        }
        for (value in listOf(0.0, 0.2, 100.0)) assertEquals(value, window.copy(usedPercent = value).validPercent)
    }
}
