package io.hydrion.hub.ui.chrome

import android.animation.ValueAnimator
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.*
import androidx.compose.ui.util.lerp
import io.hydrion.hub.R
import io.hydrion.hub.provider.ChatModelPickerBack
import io.hydrion.hub.provider.ChatPickerEasing
import io.hydrion.hub.provider.chatModelPickerPreImeBack
import io.hydrion.hub.ui.ChatPanelMotionMillis
import io.hydrion.hub.ui.messages.LocalMessageStyle
import io.hydrion.hub.ui.messages.timelineScheme
import io.hydrion.hub.ui.theme.HubShapes
import kotlin.math.abs
import kotlin.math.roundToInt

internal class ContextWindowState {
    var open by mutableStateOf(false)
    var anchor by mutableStateOf(Rect.Zero)
    var panel = Rect.Zero
    var hostOrigin = Offset.Zero
}

internal fun Modifier.contextWindowInput(state: ContextWindowState): Modifier {
    if (!state.open) return this
    return onGloballyPositioned { state.hostOrigin = it.positionInRoot() }
        .chatModelPickerPreImeBack(true) { state.open = false }
        .pointerInput(state) {
            // Observe the same press the composer or another header action will receive.
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                val point = down.position + state.hostOrigin
                if (!state.anchor.contains(point) && !state.panel.contains(point)) {
                    // Let the target handle Up before removing this ancestor from the hit path.
                    do { val event = awaitPointerEvent(PointerEventPass.Final) } while (event.changes.any { it.pressed })
                    state.open = false
                }
            }
        }
}

private class ContextShell(private val source: Rect, private val progress: Float) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val height = lerp(source.height, size.height, progress)
        val radius = lerp(source.height / 2, with(density) { HubShapes.LargeRadius.toPx() }, progress)
        return Outline.Rounded(RoundRect(lerp(source.left, 0f, progress), 0f,
            lerp(source.right, size.width, progress), height, CornerRadius(radius)))
    }
}

@Composable
internal fun ContextWindowOverlay(state: ContextWindowState, usage: ContextUsage, onManage: (() -> Unit)?, modifier: Modifier = Modifier) {
    val reveal = remember(state) { Animatable(0f) }
    val systemScale = rememberCoroutineScope().coroutineContext[MotionDurationScale]
    val animate = (systemScale?.scaleFactor ?: 1f) > 0f && ValueAnimator.areAnimatorsEnabled()
    LaunchedEffect(state.open, animate) {
        val end = if (state.open) 1f else 0f
        if (animate) reveal.animateTo(end, tween((ChatPanelMotionMillis * abs(end - reveal.value)).roundToInt(), easing = ChatPickerEasing))
        else reveal.snapTo(end)
    }
    if (state.open) ChatModelPickerBack({ state.open = false })
    val visible by remember(state, reveal) { derivedStateOf { state.open || reveal.value > 0f } }
    if (!visible) return

    var origin by remember { mutableStateOf<Offset?>(null) }
    DisposableEffect(state) { onDispose { state.panel = Rect.Zero } }
    BoxWithConstraints(modifier.onGloballyPositioned { origin = it.positionInRoot() }, contentAlignment = AbsoluteAlignment.TopLeft) {
        val viewport = origin ?: return@BoxWithConstraints
        val anchor = state.anchor.translate(-viewport)
        if (anchor.isEmpty) return@BoxWithConstraints
        val gutter = 12.dp
        val width = minOf(396.dp, maxWidth - gutter * 2)
        val density = LocalDensity.current
        val top = with(density) { anchor.bottom.toDp() } + 4.dp
        val height = maxHeight - top - 4.dp
        if (width <= 0.dp || height <= 0.dp) return@BoxWithConstraints
        val left = with(density) {
            (anchor.center.x - width.toPx() / 2).coerceIn(gutter.toPx(), (maxWidth - gutter - width).toPx().coerceAtLeast(gutter.toPx()))
        }
        val source = anchor.translate(-left, 0f)
        val title = stringResource(R.string.context_window)
        val colors = timelineScheme(LocalMessageStyle.current.tone)
        val surface = if (colors.surface.luminance() < colors.onSurface.luminance()) Color(0xFF1E1E1E) else colors.surfaceContainerHigh
        // On return the live title pill stays in front of the moving shell. Clip in viewport
        // coordinates before the panel offset so even its last frame leaves the title visible.
        val returnClip = if (state.open) Modifier else Modifier.drawWithCache {
            val pill = Path().apply { addRoundRect(RoundRect(anchor, CornerRadius(anchor.size.minDimension / 2f))) }
            onDrawWithContent {
                clipPath(pill, ClipOp.Difference) { this@onDrawWithContent.drawContent() }
            }
        }
        Box(returnClip.absoluteOffset { IntOffset(left.roundToInt(), top.roundToPx()) }
            .width(width).heightIn(max = height).onGloballyPositioned {
                // Hit-test the settled panel, independent of the animated outline's clipping.
                state.panel = Rect(viewport + Offset(left, with(density) { top.toPx() }), it.size.toSize())
            }
            .graphicsLayer {
                val p = reveal.value
                translationY = (anchor.top - top.toPx()) * (1f - p)
                shape = ContextShell(source, p)
                clip = true
                shadowElevation = 6.dp.toPx() * p
            }
            .background(surface).pointerInput(Unit) {}
            .then(if (state.open) Modifier.semantics { paneTitle = title } else Modifier.clearAndSetSemantics {})
            .testTag("context-window")) {
            MaterialTheme(colorScheme = colors) {
                ContextWindowPanel(usage, onManage, Modifier.graphicsLayer {
                    alpha = ((reveal.value - 0.15f) / 0.85f).coerceIn(0f, 1f)
                    translationY = -8.dp.toPx() * (1f - reveal.value)
                })
            }
        }
    }
}
