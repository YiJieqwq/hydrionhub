package io.hydrion.hub.provider

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.sin
import kotlin.random.Random

internal class RailStar(val y: Float, val brightness: Float, val seconds: Double, advance: Double) {
    val initialPhase = advance / seconds % 1.0
}

internal const val RailStarClearanceDp = 2f

internal val railStars = Random(713).let { random ->
    List(22) { i -> RailStar(.08f + .84f * random.nextFloat(), .5f + .5f * random.nextFloat(),
        1.6 + 2.4 * random.nextDouble(), 4.0 * ((i * .61803398875) % 1.0)) }
}

internal fun starDrawX(phase: Float, fillWidth: Float): Float = (-.04f + 1.08f * phase) * fillWidth

internal fun railFillWidth(width: Float, thumbRadius: Float, fraction: Float): Float {
    val radius = minOf(thumbRadius, width / 2)
    return radius + (width - 2 * radius) * fraction.coerceIn(0f, 1f)
}

internal fun fastStarSpeedScale(fillWidth: Float, referenceFillWidth: Float): Double =
    if (fillWidth > 0f && referenceFillWidth > 0f) referenceFillWidth.toDouble() / fillWidth else 1.0

internal fun starDrawY(fraction: Float, railHeight: Float, radius: Float, edgeGap: Float): Float {
    val inset = minOf(radius + edgeGap, railHeight / 2)
    return inset + fraction * (railHeight - 2 * inset)
}

/** All tiers share the same stars and clock, including while switching Fast on or off. */
internal class RailStarMotion {
    private var driftSeconds = 0.0
    private var travelSeconds = 0.0
    private var fastFraction = 0.0

    fun advance(delta: Double, fast: Boolean, rtl: Boolean, speedScale: Double = 1.0) {
        val target = if (fast) 1.0 else 0.0
        val remaining = abs(target - fastFraction) * RampSeconds
        val rampTime = minOf(delta, remaining)
        val next = when {
            delta >= remaining -> target
            fast -> fastFraction + rampTime / RampSeconds
            else -> fastFraction - rampTime / RampSeconds
        }
        // Integrate both velocities over the frame. Reversals start from the current
        // speed and position; a frame may include the end of the ramp and steady travel.
        val fastTime = rampTime * (fastFraction + next) / 2 + (delta - rampTime) * target
        fastFraction = next
        driftSeconds += delta - fastTime
        // The RTL rail mirrors its canvas. Positive local travel there still moves left on screen.
        val travel = fastTime * speedScale
        travelSeconds += if (rtl) travel else -travel
    }

    fun phase(star: RailStar): Float {
        val drift = (.045 + .03 * star.brightness) * sin(2 * PI * (star.y + driftSeconds / (3 * star.seconds)))
        val x = star.initialPhase + drift + travelSeconds / star.seconds
        return (x - floor(x)).toFloat()
    }

    fun heightFraction(star: RailStar): Float {
        val amplitude = minOf(.18f, star.y, 1f - star.y) * .8f
        return star.y + amplitude * sin(2 * PI * (star.initialPhase + driftSeconds / (6 + star.seconds))).toFloat()
    }

    private companion object {
        const val RampSeconds = .18
    }
}
