package io.hydrion.hub.provider

import kotlin.math.PI
import kotlin.math.floor
import kotlin.math.sin
import kotlin.random.Random

internal class RailStar(val y: Float, val brightness: Float, val seconds: Double, advance: Double) {
    val initialPhase = advance / seconds % 1.0
}

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
    private var fastAge = 0.0

    fun advance(delta: Double, fast: Boolean, rtl: Boolean, speedScale: Double = 1.0) {
        if (!fast) {
            driftSeconds += delta
            fastAge = 0.0
            return
        }
        // Integrate a linear speed ramp, including a frame that crosses the end of the ramp.
        val accelerating = minOf(delta, RampSeconds - fastAge)
        val rampDistance = accelerating * (fastAge + accelerating / 2) / RampSeconds
        fastAge = (fastAge + accelerating).coerceAtMost(RampSeconds)
        // The RTL rail mirrors its canvas. Positive local travel there still moves left on screen.
        val travel = (rampDistance + delta - accelerating) * speedScale
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
