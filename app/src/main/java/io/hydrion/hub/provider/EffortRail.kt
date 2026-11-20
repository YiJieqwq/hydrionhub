package io.hydrion.hub.provider

import android.animation.ValueAnimator
import androidx.compose.animation.core.withInfiniteAnimationFrameNanos
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.collectLatest
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

private data class Spark(val x: Float, val phase: Float, val radiusDp: Float, val alpha: Float)

@Composable
internal fun EffortRail(index: Int, lastIndex: Int, accent: Color, peak: Boolean, accelerated: Boolean) {
    val rail = MaterialTheme.colorScheme.outlineVariant
    val tick = MaterialTheme.colorScheme.outline
    val sparks = remember {
        val random = Random(731)
        List(18) {
            Spark(random.nextFloat(), random.nextFloat() * (2 * PI).toFloat(),
                .55f + random.nextFloat() * .55f, .55f + random.nextFloat() * .4f)
        }
    }
    val drift = remember { mutableFloatStateOf(0f) }
    val leftTravel = remember { mutableFloatStateOf(0f) }
    if (peak || accelerated) {
        LaunchedEffect(accelerated) {
            val scale = coroutineContext[MotionDurationScale]
            snapshotFlow { scale?.scaleFactor ?: if (ValueAnimator.areAnimatorsEnabled()) 1f else 0f }
                .collectLatest { factor ->
                    if (factor <= 0f) return@collectLatest
                    var previous = withInfiniteAnimationFrameNanos { it }
                    while (true) {
                        withInfiniteAnimationFrameNanos { now ->
                            val seconds = ((now - previous) / 1_000_000_000f).coerceIn(0f, .1f) / factor
                            previous = now
                            if (accelerated) leftTravel.floatValue = (leftTravel.floatValue + seconds * .85f) % 1f
                            else drift.floatValue = (drift.floatValue + seconds * .36f) % (2 * PI).toFloat()
                        }
                    }
                }
        }
    }
    Canvas(Modifier.fillMaxWidth().height(18.dp)) {
        val rtl = layoutDirection == LayoutDirection.Rtl
        fun notch(i: Int): Offset {
            if (lastIndex == 0) return center
            val x = size.width * i / lastIndex
            return Offset(if (rtl) size.width - x else x, center.y)
        }
        val start = Offset(if (rtl) size.width else 0f, center.y)
        val railEnd = Offset(if (rtl) 0f else size.width, center.y)
        val end = if (lastIndex == 0) railEnd else notch(index)
        drawLine(rail, start, railEnd, 2.dp.toPx(), StrokeCap.Round)
        if (peak) {
            drawLine(accent.copy(alpha = .06f), start, end, 15.dp.toPx(), StrokeCap.Round)
            drawLine(accent.copy(alpha = .10f), start, end, 9.dp.toPx(), StrokeCap.Round)
            drawLine(accent.copy(alpha = .18f), start, end, 5.dp.toPx(), StrokeCap.Round)
        }
        drawLine(accent, start, end, 2.dp.toPx(), StrokeCap.Round)
        for (i in 0..lastIndex) drawCircle(if (i <= index) accent else tick, 2.dp.toPx(), notch(i))

        if (peak || accelerated) {
            // Only draw observes the clocks. Fast travel is screen-left even in RTL; drift pauses there.
            val t = drift.floatValue
            val distance = leftTravel.floatValue
            for ((i, spark) in sparks.withIndex()) {
                val dx = sin(spark.phase + t * (1 + i % 3)) * .045f
                val x = ((spark.x + dx - distance + 2f) % 1f) * size.width
                val y = center.y + cos(spark.phase + t * (1 + i % 2)) * 5.dp.toPx()
                drawCircle(Color.White.copy(alpha = spark.alpha), spark.radiusDp.dp.toPx(), Offset(x, y))
            }
        }
    }
}
