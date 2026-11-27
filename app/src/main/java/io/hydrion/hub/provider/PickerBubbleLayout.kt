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
import androidx.compose.ui.geometry.lerp as lerpRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.*
import io.hydrion.hub.ui.ChatPanelMotionMillis
import io.hydrion.hub.ui.LocalPickerModelTravelX
import io.hydrion.hub.ui.LocalPickerModelLabelBounds
import io.hydrion.hub.ui.LocalPickerComposerBounds
import io.hydrion.hub.ui.theme.HubShapes
import kotlin.math.abs
import kotlin.math.roundToInt

internal const val PickerBubbleMotionMillis = ChatPanelMotionMillis

internal val ColorScheme.pickerBubbleHighlight: Color get() = onSurface.copy(alpha = 0.18f)

private data class BubbleTargets(
    val label: Rect, val effort: Rect, val list: Rect, val composer: IntRect, val above: Boolean, val travelX: Int = 0,
) {
    fun bounds(weights: Offset) = translatedPickerBounds(label, effort, list, weights, travelX)
}

private data class PagePlacement(val topLeft: Offset, val scale: Float = 1f)

private fun BubbleTargets.pagePlacement(face: PickerBubbleFace, w: Offset, slide: Float): PagePlacement {
    val panel = if (face == PickerBubbleFace.Effort) effort else list
    // The capability page still slides sideways when it replaces the effort controls.
    val dx = if (face == PickerBubbleFace.Effort) -w.y * slide else (1f - w.y) * slide
    val offset = Offset(travelX + dx, pickerContentShiftY(dx, panel, composer, above))
    if (face != PickerBubbleFace.Effort || panel.isEmpty) return PagePlacement(panel.topLeft + offset)
    val frame = lerpRect(label, effort, (w.x + w.y).coerceIn(0f, 1f))
    val scale = minOf(frame.width / effort.width, frame.height / effort.height, 1f)
    return PagePlacement(frame.center - (effort.center - effort.topLeft) * scale + offset, scale)
}

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
    modelBrowseProgress: () -> Float, browsingModels: Boolean,
    listColor: Color, effortColor: Color, effortBorder: Color,
    modifier: Modifier, paneTitle: String, content: @Composable () -> Unit,
) {
    val motion = remember(picker) { Animatable(Offset.Zero, Offset.VectorConverter) }
    val durationScale = rememberCoroutineScope().coroutineContext[MotionDurationScale]
    val progress by rememberUpdatedState(modelBrowseProgress)
    val atComposer by remember { derivedStateOf { progress() <= 0f } }
    var previousLeg by remember(picker) { mutableStateOf<BrowseEffortLeg?>(null) }
    // The model page is a tools-style page. Its existing clock owns both the toolbar and
    // this handoff. Only ordinary label/effort/capability popups use the local tween.
    val shared = browsingModels || (!atComposer && face != PickerBubbleFace.List && !hideForReturn)
    val leg = remember(picker, shared, browsingModels, face) {
        if (!shared) null else {
            val p = progress()
            val target = if (browsingModels) 1f else 0f
            val showEffort = if (face == PickerBubbleFace.Effort) 1f else 0f
            BrowseEffortLeg(p, target, previousLeg?.coverage(p) ?: motion.value.x, showEffort,
                previousLeg?.alpha(p) ?: if (motion.value.x > 0f) 1f else 0f, showEffort)
        }
    }
    SideEffect { previousLeg = leg }
    val weights = { leg?.let { Offset(it.coverage(progress()), 0f) } ?: motion.value }
    val shellAlpha = { leg?.alpha(progress()) ?: 1f }
    LaunchedEffect(face, hideForReturn, shared) {
        if (hideForReturn || shared) motion.snapTo(if (hideForReturn) Offset.Zero else face.weights) else {
            val delta = face.weights - motion.value
            val distance = maxOf(abs(delta.x), abs(delta.y), abs(delta.x + delta.y))
            motion.animateTo(face.weights, tween((PickerBubbleMotionMillis * distance).roundToInt(), easing = ChatPickerEasing))
        }
    }
    val atSource by remember(motion, leg) { derivedStateOf { weights() == Offset.Zero } }
    if (face == PickerBubbleFace.Label && atSource) return
    DisposableEffect(picker) {
        picker.drawingBubble = true
        onDispose { picker.drawingBubble = false }
    }

    val targets = remember(picker) { mutableStateOf(BubbleTargets(Rect.Zero, Rect.Zero, Rect.Zero, IntRect.Zero, true)) }
    val measuredAnchor = LocalPickerComposerBounds.current
    val measuredLabel = LocalPickerModelLabelBounds.current
    val measuredTravel = LocalPickerModelTravelX.current
    val effortInk = rememberGraphicsLayer()
    val listInk = rememberGraphicsLayer()
    var effortSize by remember(picker) { mutableStateOf(IntSize.Zero) }
    var listSize by remember(picker) { mutableStateOf(IntSize.Zero) }
    val colors = MaterialTheme.colorScheme
    val currentInk = if (face == PickerBubbleFace.Effort) effortInk else listInk
    // Preserve the outgoing capability menu's surface during a return to the effort card.
    var lastListColor by remember { mutableStateOf(listColor) }
    if (face == PickerBubbleFace.List) SideEffect { lastListColor = listColor }

    Layout(modifier = modifier, content = {
        BubbleSurface(picker.effortTouches, targets, weights, shellAlpha, colors.pickerBubbleHighlight, effortColor,
            if (face == PickerBubbleFace.List) listColor else lastListColor, effortBorder, colors.outlineVariant,
            hideForReturn, if (shared) PickerBubbleFace.Label else face, velocity = {
                if (!shared && motion.isRunning && motion.targetValue == face.weights && motion.value != face.weights) {
                    val scale = durationScale?.scaleFactor ?: if (ValueAnimator.areAnimatorsEnabled()) 1f else 0f
                    if (scale > 0f) motion.velocity / scale else Offset.Zero
                } else Offset.Zero
            })
        // Paint effort in viewport coordinates, including over its source label. Its hit region
        // stays outside the composer. A retired page contributes only its last drawing.
        Spacer(Modifier.fillMaxSize().drawBehind {
            val w = weights()
            if (hideForReturn || w == Offset.Zero) return@drawBehind
            val scene = targets.value
            val rect = scene.bounds(w)
            fun paint(page: PickerBubbleFace, ink: GraphicsLayer, alpha: Float) {
                if (alpha <= 0f || ink.size == IntSize.Zero) return
                ink.alpha = alpha
                val placement = scene.pagePlacement(page, w, 14.dp.toPx())
                translate(placement.topLeft.x, placement.topLeft.y) {
                    scale(placement.scale, pivot = Offset.Zero) { drawLayer(ink) }
                }
            }
            if (w.x > 0f || (face != PickerBubbleFace.List && w.y > 0f)) {
                val radius = bubbleRadius(rect, HubShapes.SmallRadius.toPx(), w.x + w.y)
                clipPath(Path().apply { addRoundRect(RoundRect(rect, CornerRadius(radius))) }) {
                    if (face == PickerBubbleFace.Effort) paint(PickerBubbleFace.List, listInk, w.y)
                    paint(PickerBubbleFace.Effort, effortInk, w.x)
                    if (face == PickerBubbleFace.Label) paint(PickerBubbleFace.List, listInk, w.y)
                }
            }
        })
        if (face != PickerBubbleFace.Label) {
            val pageSemantics = if (shared) Modifier.clearAndSetSemantics {} else Modifier.semantics { this.paneTitle = paneTitle }
            val pageHit = if (face == PickerBubbleFace.Effort) picker.effortTouches.card(pageSemantics) else pageSemantics
            Box(pageHit.drawWithContent {
                currentInk.record { this@drawWithContent.drawContent() }
                if (face == PickerBubbleFace.List) {
                    currentInk.alpha = 1f
                    drawLayer(currentInk)
                }
            }) {
                CompositionLocalProvider(LocalContentColor provides colors.onSurface, content = content)
                if (shared) Spacer(Modifier.matchParentSize().pointerInput(Unit) {})
            }
        }
    }) { children, limits ->
        val viewport = IntSize(limits.maxWidth, limits.maxHeight)
        // Model configuration moves the whole dock on a graphics layer. Its suspended
        // selector follows window coordinates; interactive browsing uses this measure pass.
        val localBar = measuredAnchor?.takeUnless { hideForReturn }
        val anchor = localBar ?: origin?.let { picker.composerBounds?.translate(-it) }
            ?: return@Layout layout(viewport.width, viewport.height) {}
        val travel = if (shared && localBar != null && measuredLabel != null) measuredTravel else 0
        val label = if (localBar != null) measuredLabel?.translate(IntOffset(-travel, 0))
            else origin?.let { picker.modelLabelBounds?.translate(-it) }
        val trigger = if (localBar == null) origin?.let { picker.triggerBounds?.translate(-it) } else null
        val effort = pickerSpace(anchor, viewport, layoutDirection, reasoning = true)
        val list = pickerSpace(anchor, viewport, layoutDirection)
        val space = if (face == PickerBubbleFace.Effort) effort else list
        val live = children.getOrNull(2)?.measure(
            Constraints(minWidth = space.width, maxWidth = space.width, maxHeight = space.maxHeight),
        )
        if (live != null) {
            val size = IntSize(live.width, live.height)
            if (face == PickerBubbleFace.Effort) effortSize = size else listSize = size
        }
        val scene = BubbleTargets(
            pickerBubbleLabel(label, trigger, anchor, viewport, layoutDirection),
            effort.panelRect(effortSize.height), list.panelRect(live?.height?.takeIf { face == PickerBubbleFace.List } ?: listSize.height),
            anchor, space.above, travel,
        )
        targets.value = scene
        val shell = children[0].measure(limits)
        val ink = children[1].measure(limits)
        layout(viewport.width, viewport.height) {
            shell.place(0, 0)
            if (face != PickerBubbleFace.Effort) ink.place(0, 0)
            if (live != null) {
                val panel = if (face == PickerBubbleFace.Effort) scene.effort else scene.list
                live.placeWithLayer(panel.left.roundToInt() + scene.travelX, panel.top.roundToInt()) {
                    val w = weights()
                    val placement = scene.pagePlacement(face, w, 14.dp.toPx())
                    transformOrigin = TransformOrigin(0f, 0f)
                    scaleX = placement.scale
                    scaleY = placement.scale
                    translationX = placement.topLeft.x - panel.left - scene.travelX
                    translationY = placement.topLeft.y - panel.top
                    alpha = if (hideForReturn) 0f else if (face == PickerBubbleFace.Effort) 1f else w.y
                    val rect = scene.bounds(w)
                    val hit = if (face != PickerBubbleFace.Effort) rect
                        else if (scene.above) rect.copy(bottom = scene.composer.top.toFloat().coerceIn(rect.top, rect.bottom))
                        else rect.copy(top = scene.composer.bottom.toFloat().coerceIn(rect.top, rect.bottom))
                    val inverse = if (placement.scale > 0f) 1f / placement.scale else 0f
                    shape = BubbleClip(Rect((hit.topLeft - placement.topLeft) * inverse, (hit.bottomRight - placement.topLeft) * inverse),
                        bubbleRadius(rect, HubShapes.SmallRadius.toPx(), w.x + w.y) * inverse)
                    clip = true
                }
            }
            if (face == PickerBubbleFace.Effort) ink.place(0, 0)
        }
    }
}

// Only the lightweight shell changes measured size per animation frame. The live page measures
// at its final width, so neither the LazyColumn nor the surrounding chat reflows during a morph.
@Composable
private fun BubbleSurface(
    touches: EffortCardTouches, targets: State<BubbleTargets>, weights: () -> Offset, shellAlpha: () -> Float, highlight: Color, effort: Color, list: Color,
    effortBorder: Color, listBorder: Color, hidden: Boolean, face: PickerBubbleFace, velocity: () -> Offset,
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
            bubbleRadius(rect, HubShapes.SmallRadius.toPx(), cover), edge, trailPath)
    }, content = {
        Spacer(Modifier.testTag("chat-picker-bubble").drawBehind {
            val w = weights()
            val cover = (w.x + w.y).coerceIn(0f, 1f)
            val listPart = if (cover > 0f) (w.y / cover).coerceIn(0f, 1f) else 0f
            val radius = CornerRadius(bubbleRadius(Rect(Offset.Zero, size), HubShapes.SmallRadius.toPx(), cover))
            val surface = lerp(highlight, lerp(effort, list, listPart), cover)
            drawRoundRect(surface, cornerRadius = radius)
            // The effort outline is two device pixels; the capability menu uses its original dp width.
            val stroke = 2f + (0.66.dp.toPx() - 2f) * listPart
            val edge = lerp(effortBorder, listBorder, listPart)
            if (cover > 0f && size.minDimension > stroke) {
                drawRoundRect(edge.copy(alpha = edge.alpha * cover), topLeft = Offset(stroke / 2, stroke / 2),
                    size = Size(size.width - stroke, size.height - stroke),
                    cornerRadius = CornerRadius((radius.x - stroke / 2).coerceAtLeast(0f)), style = Stroke(stroke))
            }
        })
        if (face != PickerBubbleFace.Label) CompositionLocalProvider(LocalViewConfiguration provides exactPickerHitBounds()) {
            Spacer(if (face == PickerBubbleFace.Effort) touches.card(Modifier) else Modifier.pointerInput(Unit) {})
        }
    }) { children, limits ->
        val w = weights()
        val scene = targets.value
        val rect = scene.bounds(w)
        val x = rect.left.roundToInt()
        val y = rect.top.roundToInt()
        val shell = children.first().measure(Constraints.fixed((rect.right.roundToInt() - x).coerceAtLeast(0),
            (rect.bottom.roundToInt() - y).coerceAtLeast(0)))
        // The painted shell travels to the model label. Only its area outside the composer takes taps.
        val hitY = if (scene.above) y else maxOf(y, scene.composer.bottom)
        val hitEnd = if (scene.above) minOf(y + shell.height, scene.composer.top) else y + shell.height
        val hit = children.getOrNull(1)?.measure(Constraints.fixed(shell.width, (hitEnd - hitY).coerceAtLeast(0)))
        val cover = w.x + w.y
        val radius = bubbleRadius(rect, HubShapes.SmallRadius.toPx(), cover)
        layout(limits.maxWidth, limits.maxHeight) {
            if (!hidden) {
                shell.placeWithLayer(x, y) {
                    shape = RoundedCornerShape(radius)
                    alpha = shellAlpha()
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
