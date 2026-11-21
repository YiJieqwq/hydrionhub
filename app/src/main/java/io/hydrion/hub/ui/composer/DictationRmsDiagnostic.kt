package io.hydrion.hub.ui.composer

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Armed from About and consumed on the main thread when a recognition session actually starts. */
internal object DictationRmsDiagnostic {
    var armed by mutableStateOf(false)
    fun take(): RmsSummary? {
        if (!armed) return null
        armed = false
        return RmsSummary()
    }
}

internal class RmsSummary {
    private var finished = false
    private var received = 0
    private var accepted = 0
    private var rawMin = Float.POSITIVE_INFINITY
    private var rawMax = Float.NEGATIVE_INFINITY
    private var normalizedMin = Float.POSITIVE_INFINITY
    private var normalizedMax = Float.NEGATIVE_INFINITY

    fun record(raw: Float, normalized: Float?) {
        if (finished) return
        received++
        if (!raw.isFinite()) return
        rawMin = minOf(rawMin, raw)
        rawMax = maxOf(rawMax, raw)
        if (normalized != null) {
            accepted++
            normalizedMin = minOf(normalizedMin, normalized)
            normalizedMax = maxOf(normalizedMax, normalized)
        }
    }

    fun finish(): String? {
        if (finished) return null
        finished = true
        fun field(value: Float) = if (value.isFinite()) value.toString() else "none"
        return "received=$received accepted=$accepted rawMin=${field(rawMin)} rawMax=${field(rawMax)} " +
            "normalizedMin=${field(normalizedMin)} normalizedMax=${field(normalizedMax)}"
    }
}
