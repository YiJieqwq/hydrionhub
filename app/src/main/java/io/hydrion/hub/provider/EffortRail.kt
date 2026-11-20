package io.hydrion.hub.provider

import android.animation.ValueAnimator
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.withInfiniteAnimationFrameNanos
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.structuralEqualityPolicy
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

private const val BurstMillis = 950
private val ToneEase = CubicBezierEasing(.25f, .1f, .25f, 1f)
private val FlowEase = CubicBezierEasing(.42f, 0f, .58f, 1f)
private val BurstEase = CubicBezierEasing(.15f, .72f, .25f, 1f)
private val GradientStops = listOf(0f, .10f, .20f, .30f, .40f, .50f, .55f, .60f, .65f, .70f, .75f, .80f, .85f, .93f, 1f)
private val LightGradient = listOf(0x3b83f6, 0x3b82f6, 0x3d7bf3, 0x4c75f4, 0x6473fa, 0x8d80fc, 0x9375fd,
    0x9d78fd, 0xa779fe, 0xa476fe, 0x9e73fe, 0x9166fe, 0x845af9, 0x7d55f6, 0x7448ee)
private val DarkGradient = listOf(0x264bd8, 0x3e4cde, 0x5a4ee5, 0x7451ed, 0x8956f3, 0x955dff, 0x986afa,
    0x9b75fe, 0x9a6dfc, 0x955dff, 0x9059f8, 0x8c58f3, 0x8755ee, 0x8558ec, 0x895eeb)

private data class Mote(val near: Offset, val far: Offset, val radius: Float, val alpha: Float, val delay: Float, val life: Float)

private val motes = Random(971).let { random ->
    List(18) { i ->
        val angle = (108.0 + random.nextDouble() * 56.0) * PI / 180.0
        val vector = Offset(cos(angle).toFloat(), sin(angle).toFloat() * if (i % 2 == 0) 1f else -1f)
        val reach = .72f + .55f * random.nextFloat()
        Mote(
            near = vector * (9f + 5f * random.nextFloat()),
            far = Offset(vector.x * (34f + 12f * random.nextFloat()), vector.y * (20f + 9f * random.nextFloat())) * reach,
            radius = 1.7f + 1.3f * random.nextFloat(), alpha = .7f + .3f * random.nextFloat(),
            delay = 50f * random.nextFloat(), life = 600f + 300f * random.nextFloat(),
        )
    }
}

private fun sweep(seconds: Double, period: Double): Float {
    val cycle = (seconds / period % 2.0).toFloat()
    return FlowEase.transform(if (cycle <= 1f) cycle else 2f - cycle)
}

private fun DrawScope.glow(brush: Brush, at: Offset, radius: Float, alpha: Float) {
    translate(at.x, at.y) {
        scale(radius, pivot = Offset.Zero) { drawCircle(brush, 1f, Offset.Zero, alpha = alpha) }
    }
}

private fun DrawScope.drawBloom(origin: Offset, time: Float, tint: Color, softLight: Brush, strength: Float) {
    val haloTime = (time / 500f).coerceIn(0f, 1f)
    if (haloTime < 1f) glow(softLight, origin, (6f + 34f * haloTime).dp.toPx(), .55f * (1f - haloTime) * strength)
    for (mote in motes) {
        val age = (time - mote.delay) / mote.life
        if (age <= 0f || age >= 1f) continue
        val travel = BurstEase.transform(age)
        val point = origin + (mote.near + (mote.far - mote.near) * travel) * density
        val opacity = if (age < .18f) age / .18f else 1f - (age - .18f) / .82f
        val alpha = opacity * mote.alpha * strength
        val radius = mote.radius.dp.toPx() * (.4f + .1f * travel)
        glow(softLight, point, radius + 3.dp.toPx(), alpha * .55f)
        drawCircle(tint, radius, point, alpha = alpha)
    }
}

@Composable
internal fun EffortRail(
    position: () -> Float, thumbScale: () -> Float, stops: List<String>,
    highestIndex: Int, committedIndex: Int, accelerated: Boolean,
) {
    if (stops.isEmpty()) return
    val dark = MaterialTheme.colorScheme.surface.luminance() < .5f
    val readPosition by rememberUpdatedState(position)
    val preview by remember(stops) {
        derivedStateOf(structuralEqualityPolicy()) { (readPosition().coerceIn(0f, 1f) * stops.lastIndex).roundToInt() }
    }
    val palette = remember(stops, preview, dark) { effortPalette(stops, stops[preview], dark) }
    val peak = preview == highestIndex && highestIndex >= 0
    val peakAmount = animateFloatAsState(if (peak) 1f else 0f, tween(250, easing = ToneEase), label = "effortPeak")
    val peakMote = remember(stops, highestIndex, dark) { effortPalette(stops, stops.getOrNull(highestIndex), dark).mote }
    val fastBlue = remember(dark) { ordinaryFastRailColors(dark) }
    val railPalette = if (accelerated && !peak) fastBlue else palette
    val fill = animateColorAsState(railPalette.fill, tween(250, easing = ToneEase), label = "effortFill")
    val track = animateColorAsState(railPalette.track, tween(250, easing = ToneEase), label = "effortTrack")
    val ticks = animateFloatAsState(if (peak) 0f else 1f, tween(180, easing = ToneEase), label = "effortTicks")
    val dust = animateFloatAsState(if (peak || accelerated) 1f else 0f, tween(250, easing = ToneEase), label = "effortDust")
    val hasDust by remember(dust) { derivedStateOf(structuralEqualityPolicy()) { dust.value > 0f } }
    val seconds = remember { mutableDoubleStateOf(0.0) }
    val motion = remember { RailStarMotion() }
    val fast by rememberUpdatedState(accelerated)
    val rtl by rememberUpdatedState(LocalLayoutDirection.current == LayoutDirection.Rtl)
    var widthPx by remember { mutableIntStateOf(0) }
    val maxThumbRadius = with(LocalDensity.current) { 15.dp.toPx() }
    // An initially selected peak uses the adjacent ordinary stop as its speed reference.
    var ordinaryIndex by remember(stops, highestIndex) { mutableIntStateOf((highestIndex - 1).coerceAtLeast(0)) }
    SideEffect {
        if (committedIndex in stops.indices && committedIndex != highestIndex) ordinaryIndex = committedIndex
    }
    val speedScale by rememberUpdatedState {
        if (stops.size > 1) {
            val width = widthPx.toFloat()
            val reference = railFillWidth(width, maxThumbRadius, ordinaryIndex.toFloat() / stops.lastIndex)
            fastStarSpeedScale(railFillWidth(width, maxThumbRadius, readPosition()), reference)
        } else 1.0
    }
    if (peak || accelerated || hasDust) {
        LaunchedEffect(Unit) {
            val scale = coroutineContext[MotionDurationScale]
            snapshotFlow { scale?.scaleFactor ?: if (ValueAnimator.areAnimatorsEnabled()) 1f else 0f }
                .collectLatest { factor ->
                    if (factor <= 0f) return@collectLatest
                    var previous = withInfiniteAnimationFrameNanos { it }
                    while (true) withInfiniteAnimationFrameNanos { now ->
                        val delta = (now - previous) / 1_000_000_000.0 / factor
                        motion.advance(delta, fast, rtl, speedScale())
                        seconds.doubleValue += delta
                        previous = now
                    }
                }
        }
    }
    val bloom = remember(stops, highestIndex) { Animatable(1f) }
    val scope = rememberCoroutineScope()
    var previousIndex by remember(stops, highestIndex) { mutableIntStateOf(committedIndex) }
    LaunchedEffect(committedIndex, bloom) {
        val arrived = highestIndex >= 0 && committedIndex == highestIndex && previousIndex != highestIndex
        previousIndex = committedIndex
        if (arrived && !bloom.isRunning && (coroutineContext[MotionDurationScale]?.scaleFactor ?: 1f) > 0f &&
            ValueAnimator.areAnimatorsEnabled()) {
            // Keep the burst clock alive across a quick exit/re-entry; the rail opacity fades it out.
            scope.launch {
                bloom.snapTo(0f)
                bloom.animateTo(1f, tween(BurstMillis, easing = LinearEasing))
            }
        }
    }

    Spacer(Modifier.fillMaxWidth().height(30.dp).onSizeChanged { widthPx = it.width }.drawWithCache {
        val railHeight = EffortRailHeight.toPx()
        val top = (size.height - railHeight) / 2
        val y = size.height / 2
        val radius = minOf(maxThumbRadius, size.width / 2)
        val travel = size.width - 2 * radius
        // Include a pixel of antialiasing outside the star's painted radius.
        val starEdgeGap = 1f + RailStarClearanceDp.dp.toPx()
        val clip = Path().apply {
            addRoundRect(RoundRect(0f, top, size.width, top + railHeight, CornerRadius(railHeight / 2)))
        }
        val colors = if (dark) DarkGradient else LightGradient
        val stopsWithColors = GradientStops.mapIndexed { i, fraction -> fraction to Color(colors[i] or 0xff000000.toInt()) }.toTypedArray()
        val flow = Brush.horizontalGradient(*stopsWithColors, startX = 0f, endX = 1f)
        val shineWidth = size.width + 80.dp.toPx()
        val gleam = if (dark) Color(0xFFDBC9FF) else Color.White
        val shine = Brush.linearGradient(
            .18f to Color.Transparent, .35f to gleam.copy(alpha = .03f),
            .48f to gleam.copy(alpha = if (dark) .13f else .19f),
            .66f to (if (dark) Color(0x146336CC) else Color.White.copy(alpha = .024f)), .84f to Color.Transparent,
            start = Offset(0f, -.088f * shineWidth), end = Offset(shineWidth, .088f * shineWidth),
        )
        val softLight = Brush.radialGradient(0f to peakMote, .66f to peakMote.copy(alpha = 0f),
            center = Offset.Zero, radius = 1f)
        val shadow = Brush.radialGradient(.72f to Color.Black.copy(alpha = if (dark) .45f else .15f),
            1f to Color.Transparent, center = Offset.Zero, radius = 1f)
        onDrawBehind {
            if (size.width <= 0f) return@onDrawBehind
            val fraction = position().coerceIn(0f, 1f)
            val knob = Offset(railFillWidth(size.width, maxThumbRadius, fraction), y)
            val timer = seconds.doubleValue
            val purple = peakAmount.value
            scale(if (layoutDirection == LayoutDirection.Rtl) -1f else 1f, 1f, pivot = center) {
                clipPath(clip) {
                    drawRect(track.value)
                    clipRect(right = knob.x) {
                        if (purple < 1f) drawRect(fill.value)
                        if (purple > 0f) {
                            // A 155%-wide gradient moves between both ends of the filled section.
                            translate(left = -.55f * knob.x * sweep(timer, 3.8)) {
                                scale(1.55f * knob.x, 1f, pivot = Offset.Zero) {
                                    drawRect(flow, size = Size(1f, size.height), alpha = purple)
                                }
                            }
                            translate(left = -40.dp.toPx() + (sweep(timer, 4.5) - .5f) * .9f * shineWidth) {
                                drawRect(shine, size = Size(shineWidth, size.height), alpha = purple)
                            }
                        }
                    }
                    if (ticks.value > 0f) for (i in stops.indices) {
                        val tick = if (stops.size == 1) .5f else i.toFloat() / stops.lastIndex
                        drawCircle(if (i <= preview) palette.passedTick else palette.tick, 2.dp.toPx(),
                            Offset(radius + travel * tick, y), alpha = ticks.value)
                    }
                    if (dust.value > 0f) clipRect(right = knob.x) {
                        for (star in railStars) {
                            val phase = motion.phase(star)
                            val starRadius = 1.5.dp.toPx() * star.brightness
                            val height = starDrawY(motion.heightFraction(star), railHeight, starRadius, starEdgeGap)
                            val alpha = minOf(phase / .06f, (1f - phase) / .06f, 1f) * star.brightness
                            drawCircle(Color.White, starRadius,
                                Offset(starDrawX(phase, knob.x), top + height), alpha = alpha * dust.value)
                        }
                    }
                    if (purple > 0f && bloom.value < 1f && stops.lastIndex > 0) {
                        val origin = Offset(radius + travel * highestIndex / stops.lastIndex, y)
                        drawBloom(origin, bloom.value * BurstMillis, peakMote, softLight, purple)
                    }
                }
                val thumbRadius = radius * thumbScale()
                glow(shadow, knob + Offset(0f, 1.dp.toPx()), thumbRadius + 4.dp.toPx(), 1f)
                drawCircle(palette.thumb, thumbRadius, knob)
                val border = .5.dp.toPx()
                drawCircle(Color.Black.copy(alpha = .07f), (thumbRadius - border / 2).coerceAtLeast(0f), knob, style = Stroke(border))
            }
        }
    })
}
