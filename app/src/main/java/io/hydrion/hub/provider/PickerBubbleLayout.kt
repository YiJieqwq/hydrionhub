package io.hydrion.hub.provider

import android.animation.ValueAnimator
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.*
import io.hydrion.hub.ui.ChatPanelMotionMillis
import io.hydrion.hub.ui.LocalPickerComposerBounds
import kotlin.math.abs
import kotlin.math.roundToInt

internal const val PickerBubbleMotionMillis = ChatPanelMotionMillis

internal val ColorScheme.pickerBubbleHighlight: Color get() = onSurface.copy(alpha = 0.18f)

private data class BubbleTargets(val label: Rect, val effort: Rect, val list: Rect, val composer: IntRect, val above: Boolean) {
    fun bounds(weights: Offset) = pickerBubbleBounds(label, effort, list, weights)
}

private fun PickerSpace.panelRect(height: Int): Rect {
    val h = height.coerceIn(0, maxHeight)
    val y = if (above) edgeY - h else edgeY
    return Rect(left.toFloat(), y.toFloat(), (left + width).toFloat(), (y + h).toFloat())
}

// Text keeps its native scale. Only the shell grows; old and new controls slide across its edge.
private fun contentTravel(face: PickerBubbleFace, w: Offset, distance: Float): Float =
    if (face == PickerBubbleFace.Effort) (1f - w.x - 2f * w.y) * distance else (1f - w.y) * distance

private fun bubbleRadius(bounds: Rect, panelRadius: Float, coverage: Float): Float {
    val half = bounds.minDimension * 0.5f
    return minOf(half, panelRadius + (1f - coverage) * (half - panelRadius).coerceAtLeast(0f))
}

private class BubbleClip(private val rect: Rect, private val radius: Float) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline =
        Outline.Rounded(RoundRect(rect, CornerRadius(radius)))
}

@Composable
internal fun PickerBubbleLayout(
    picker: ChatModelPickerState, face: PickerBubbleFace, origin: IntOffset?, hideForReturn: Boolean,
    freezeListSize: Boolean, listColor: Color, effortColor: Color, effortBorder: Color,
    modifier: Modifier, paneTitle: String, content: @Composable () -> Unit,
) {
    val motion = remember(picker) { Animatable(Offset.Zero, Offset.VectorConverter) }
    val durationScale = rememberCoroutineScope().coroutineContext[MotionDurationScale]
    LaunchedEffect(face, hideForReturn) {
        if (hideForReturn) motion.snapTo(Offset.Zero) else {
            val delta = face.weights - motion.value
            val distance = maxOf(abs(delta.x), abs(delta.y), abs(delta.x + delta.y))
            motion.animateTo(face.weights, tween((PickerBubbleMotionMillis * distance).roundToInt(), easing = ChatPickerEasing))
        }
    }
    val atSource by remember(motion) { derivedStateOf { motion.value == Offset.Zero } }
    if (face == PickerBubbleFace.Label && atSource) return

    val targets = remember(picker) { mutableStateOf(BubbleTargets(Rect.Zero, Rect.Zero, Rect.Zero, IntRect.Zero, true)) }
    val measuredAnchor = LocalPickerComposerBounds.current
    val effortInk = rememberGraphicsLayer()
    val listInk = rememberGraphicsLayer()
    var effortSize by remember(picker) { mutableStateOf(IntSize.Zero) }
    var listSize by remember(picker) { mutableStateOf(IntSize.Zero) }
    val colors = MaterialTheme.colorScheme
    val currentInk = if (face == PickerBubbleFace.Effort) effortInk else listInk
    // Preserve the outgoing menu's actual surface while the live selection switches subpages.
    var lastListColor by remember { mutableStateOf(listColor) }
    if (face == PickerBubbleFace.List) SideEffect { lastListColor = listColor }

    Layout(modifier = modifier, content = {
        BubbleSurface(targets, { motion.value }, colors.pickerBubbleHighlight, effortColor,
            if (face == PickerBubbleFace.List) listColor else lastListColor, effortBorder, colors.outlineVariant,
            hideForReturn, face != PickerBubbleFace.Label, velocity = {
                if (motion.isRunning && motion.targetValue == face.weights && motion.value != face.weights) {
                    val scale = durationScale?.scaleFactor ?: if (ValueAnimator.areAnimatorsEnabled()) 1f else 0f
                    if (scale > 0f) motion.velocity / scale else Offset.Zero
                } else Offset.Zero
            })
        // An outgoing page has no layout node, focus or callbacks. Retain only its last drawing.
        Spacer(Modifier.fillMaxSize().drawBehind {
            val w = motion.value
            if (hideForReturn || w == Offset.Zero) return@drawBehind
            val scene = targets.value
            val rect = scene.bounds(w)
            fun ghost(other: PickerBubbleFace, ink: GraphicsLayer, end: Rect, alpha: Float) {
                if (face == other || alpha <= 0f || ink.size == IntSize.Zero) return
                ink.alpha = alpha
                val travel = contentTravel(other, w, 14.dp.toPx())
                translate(end.left + travel, end.top + travel) { drawLayer(ink) }
            }
            if ((face != PickerBubbleFace.Effort && w.x > 0f) || (face != PickerBubbleFace.List && w.y > 0f)) {
                val radius = bubbleRadius(rect, 14.dp.toPx(), w.x + w.y)
                clipPath(Path().apply { addRoundRect(RoundRect(rect, CornerRadius(radius))) }) {
                    ghost(PickerBubbleFace.Effort, effortInk, scene.effort, w.x)
                    ghost(PickerBubbleFace.List, listInk, scene.list, w.y)
                }
            }
        })
        if (face != PickerBubbleFace.Label) {
            Box(Modifier.semantics { this.paneTitle = paneTitle }.drawWithContent {
                currentInk.alpha = 1f
                currentInk.record { this@drawWithContent.drawContent() }
                drawLayer(currentInk)
            }) {
                CompositionLocalProvider(LocalContentColor provides colors.onSurface, content = content)
            }
        }
    }) { children, limits ->
        val viewport = IntSize(limits.maxWidth, limits.maxHeight)
        if (origin == null) return@Layout layout(viewport.width, viewport.height) {}
        // Model configuration moves the whole dock on a graphics layer. Its suspended
        // selector follows window coordinates; interactive browsing uses this measure pass.
        val anchor = measuredAnchor?.takeUnless { freezeListSize || hideForReturn }
            ?: picker.composerBounds?.translate(-origin)
            ?: return@Layout layout(viewport.width, viewport.height) {}
        val effort = pickerSpace(anchor, viewport, layoutDirection, reasoning = true)
        val list = pickerSpace(anchor, viewport, layoutDirection)
        val space = if (face == PickerBubbleFace.Effort) effort else list
        val maxHeight = if (freezeListSize && face == PickerBubbleFace.List && listSize.width == list.width && listSize.height > 0)
            listSize.height.coerceAtMost(list.maxHeight) else space.maxHeight
        val live = children.getOrNull(2)?.measure(Constraints(minWidth = space.width, maxWidth = space.width, maxHeight = maxHeight))
        if (live != null) {
            val size = IntSize(live.width, live.height)
            if (face == PickerBubbleFace.Effort) effortSize = size else if (!freezeListSize || listSize == IntSize.Zero) listSize = size
        }
        val scene = BubbleTargets(
            pickerBubbleLabel(picker.modelLabelBounds?.translate(-origin), picker.triggerBounds?.translate(-origin), anchor, viewport),
            effort.panelRect(effortSize.height), list.panelRect(live?.height?.takeIf { face == PickerBubbleFace.List } ?: listSize.height),
            anchor, space.above,
        )
        targets.value = scene
        val shell = children[0].measure(limits)
        val ghosts = children[1].measure(limits)
        layout(viewport.width, viewport.height) {
            shell.place(0, 0)
            ghosts.place(0, 0)
            if (live != null) {
                val panel = if (face == PickerBubbleFace.Effort) scene.effort else scene.list
                live.placeWithLayer(panel.left.roundToInt(), panel.top.roundToInt()) {
                    val w = motion.value
                    val shift = contentTravel(face, w, 14.dp.toPx())
                    translationX = shift
                    translationY = shift
                    alpha = if (hideForReturn) 0f else if (face == PickerBubbleFace.Effort) w.x else w.y
                    val rect = scene.bounds(w)
                    shape = BubbleClip(rect.translate(-panel.left - shift, -panel.top - shift),
                        bubbleRadius(rect, 14.dp.toPx(), w.x + w.y))
                    clip = true
                }
            }
        }
    }
}

// Only the lightweight shell changes measured size per animation frame. The live page measures
// at its final width, so neither the LazyColumn nor the surrounding chat reflows during a morph.
@Composable
private fun BubbleSurface(
    targets: State<BubbleTargets>, weights: () -> Offset, highlight: Color, effort: Color, list: Color,
    effortBorder: Color, listBorder: Color, hidden: Boolean, interactive: Boolean, velocity: () -> Offset,
) {
    val trailPath = remember { Path() }
    Layout(modifier = Modifier.drawWithContent {
        drawContent()
        if (hidden) return@drawWithContent
        val speed = velocity()
        if (speed == Offset.Zero) return@drawWithContent
        val w = weights()
        val scene = targets.value
        val rect = scene.bounds(w)
        if (rect.isEmpty) return@drawWithContent
        val cover = (w.x + w.y).coerceIn(0f, 1f)
        val edge = lerp(effortBorder, listBorder, if (cover > 0f) (w.y / cover).coerceIn(0f, 1f) else 0f)
        drawPickerEdgeTrail(rect, scene.label, scene.effort, scene.list, speed,
            bubbleRadius(rect, 14.dp.toPx(), cover), edge, trailPath)
    }, content = {
        Spacer(Modifier.testTag("chat-picker-bubble").drawBehind {
            val w = weights()
            val cover = (w.x + w.y).coerceIn(0f, 1f)
            val listPart = if (cover > 0f) (w.y / cover).coerceIn(0f, 1f) else 0f
            val radius = CornerRadius(bubbleRadius(Rect(Offset.Zero, size), 14.dp.toPx(), cover))
            val surface = lerp(highlight, lerp(effort, list, listPart), cover)
            drawRoundRect(surface, cornerRadius = radius)
            // Effort has a two-device-pixel outline; the model list keeps its original dp width.
            val stroke = 2f + (0.66.dp.toPx() - 2f) * listPart
            val edge = lerp(effortBorder, listBorder, listPart)
            if (cover > 0f && size.minDimension > stroke) {
                drawRoundRect(edge.copy(alpha = edge.alpha * cover), topLeft = Offset(stroke / 2, stroke / 2),
                    size = Size(size.width - stroke, size.height - stroke),
                    cornerRadius = CornerRadius((radius.x - stroke / 2).coerceAtLeast(0f)), style = Stroke(stroke))
            }
        })
        if (interactive) CompositionLocalProvider(LocalViewConfiguration provides exactPickerHitBounds()) {
            Spacer(Modifier.pointerInput(Unit) {})
        }
    }) { children, limits ->
        val w = weights()
        val scene = targets.value
        val rect = scene.bounds(w)
        val x = rect.left.roundToInt()
        val y = rect.top.roundToInt()
        val shell = children.first().measure(Constraints.fixed((rect.right.roundToInt() - x).coerceAtLeast(0),
            (rect.bottom.roundToInt() - y).coerceAtLeast(0)))
        // Padding inside the visible shell blocks the outside-dismiss layer. Where the flight
        // crosses the composer, leave the text editor and trigger free to edit or reverse it.
        val hitY = if (scene.above) y else maxOf(y, scene.composer.bottom)
        val hitEnd = if (scene.above) minOf(y + shell.height, scene.composer.top) else y + shell.height
        val hit = children.getOrNull(1)?.measure(Constraints.fixed(shell.width, (hitEnd - hitY).coerceAtLeast(0)))
        val cover = w.x + w.y
        val radius = bubbleRadius(rect, 14.dp.toPx(), cover)
        layout(limits.maxWidth, limits.maxHeight) {
            if (!hidden) {
                shell.placeWithLayer(x, y) {
                    shape = RoundedCornerShape(radius)
                    shadowElevation = 6.dp.toPx() * cover
                }
                if (hit != null && hit.height > 0) hit.placeWithLayer(x, hitY) {
                    shape = BubbleClip(rect.translate(-x.toFloat(), -hitY.toFloat()), radius)
                    clip = true
                }
            }
        }
    }
}
