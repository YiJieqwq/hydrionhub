package io.hydrion.hub.ui

/** Coordinates the two reveal layers with the measured state of the returning editor. */
internal class ModelEditorReturnFrames {
    enum class Phase { Landing, Restoring, Fallback, Complete }
    enum class Event { None, Landed, ImeFallback, Finished }

    var phase = Phase.Landing
        private set
    var contentProgress = 0f
        private set
    var composerLandingProgress = 0f
        private set
    private var waitMillis = 0f
    private var idleMillis = 0f
    private var lastObserved = 0f

    fun advance(
        deltaMillis: Float, durationMillis: Float, canLand: Boolean,
        composerProgress: Float, composerSettled: Boolean,
    ): Event {
        val delta = deltaMillis.coerceAtLeast(0f)
        val step = if (durationMillis > 0f) delta / durationMillis else 1f
        val grace = maxOf(1_200f, durationMillis * 2f)
        when (phase) {
            Phase.Landing -> {
                val atLandingOnPreviousFrame = composerLandingProgress == 1f
                contentProgress = minOf(LandingPoint, contentProgress + step)
                composerLandingProgress = contentProgress / LandingPoint
                // Present the folded landing pose before changing its shape or requesting focus.
                if (!atLandingOnPreviousFrame) return Event.None
                if (canLand) {
                    phase = Phase.Restoring
                    waitMillis = 0f
                    return Event.Landed
                }
                // A configuration field or an uncooperative IME can retain its inset on exit.
                waitMillis += delta
                if (waitMillis >= grace) return fallback()
            }
            Phase.Restoring -> {
                val measured = composerProgress.coerceIn(0f, 1f)
                // Keep a final piece of travel for the measured settled layout and focused target.
                val goal = if (composerSettled) 1f else LandingPoint + (1f - LandingPoint) * minOf(measured, .95f)
                // Some IMEs report just their last inset. Slew to it instead of jumping a whole layer.
                contentProgress = maxOf(contentProgress, minOf(goal, contentProgress + step))
                if (composerSettled) {
                    idleMillis = 0f
                    if (contentProgress >= 1f) return finish()
                } else {
                    waitMillis += delta
                    idleMillis += delta
                    if (measured > lastObserved + .01f) {
                        lastObserved = measured
                        idleMillis = 0f
                    }
                    if (idleMillis >= grace || waitMillis >= grace * 3f) return fallback()
                }
            }
            Phase.Fallback -> {
                // The caller has cancelled any pending show. Give the actual layout a final frame
                // to settle; an absent window/IME must not leave navigation permanently suspended.
                waitMillis += delta
                if (composerSettled || waitMillis >= grace) {
                    contentProgress = minOf(1f, contentProgress + step)
                    if (contentProgress >= 1f) return finish()
                }
            }
            Phase.Complete -> Unit
        }
        return Event.None
    }

    private fun fallback(): Event {
        phase = Phase.Fallback
        waitMillis = 0f
        return Event.ImeFallback
    }

    private fun finish(): Event {
        phase = Phase.Complete
        return Event.Finished
    }

    private companion object {
        // A separate landing leg lets the folded composer arrive before the chat reaches the top.
        const val LandingPoint = 2f / 3f
    }
}
