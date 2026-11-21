package io.hydrion.hub.ui.composer

import org.junit.Assert.*
import org.junit.Test

class DictationRmsDiagnosticTest {
    @Test fun noCallbacksAndNoSecondSummary() {
        val summary = RmsSummary()
        assertEquals("received=0 accepted=0 rawMin=none rawMax=none normalizedMin=none normalizedMax=none", summary.finish())
        summary.record(6f, .6f)
        assertNull(summary.finish())
    }

    @Test fun includesRejectedFiniteRmsOnlyInRawRange() {
        val summary = RmsSummary()
        summary.record(Float.NaN, 0f)
        summary.record(Float.POSITIVE_INFINITY, 0f)
        summary.record(-4f, 0f)
        summary.record(-2f, 0f)
        summary.record(9f, null)
        assertEquals("received=5 accepted=2 rawMin=-4.0 rawMax=9.0 normalizedMin=0.0 normalizedMax=0.0", summary.finish())
    }

    @Test fun optInIsConsumedOnceAndCancelCanDisarm() {
        assertFalse(DictationRmsDiagnostic.armed)
        DictationRmsDiagnostic.armed = true
        assertNotNull(DictationRmsDiagnostic.take())
        assertNull(DictationRmsDiagnostic.take())
        DictationRmsDiagnostic.armed = true
        DictationRmsDiagnostic.armed = false
        assertNull(DictationRmsDiagnostic.take())
    }
}
