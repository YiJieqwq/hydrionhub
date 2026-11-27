package io.hydrion.hub.ui.composer

import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.zIndex
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.dismiss
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.hydrion.hub.R
import io.hydrion.hub.provider.ChatModelPickerState
import io.hydrion.hub.ui.behindToolsPage
import io.hydrion.hub.ui.theme.HubShapes
import kotlin.math.abs
import kotlin.math.roundToInt

private class PanelPull(val clock: () -> PanelTravelClock?, val requested: () -> Boolean) {
    fun progress() = clock()?.contentProgress ?: 0f
    fun acceptingBodyInput() = requested() && clock()?.atEnd == true
    var expandedHeight = 0
    var dragTravel = 1f
    var editorHeight = 0
    var root: LayoutCoordinates? = null
    var hiddenHeight by mutableIntStateOf(0)
    var sourceScale by mutableFloatStateOf(1f)
}

private val LocalPanelPull = compositionLocalOf<PanelPull?> { null }
private val HandleWidth = 100.dp
private val HandleHeight = 40.dp

/** Both children live inside the composer's original Surface. Measure the list at its
 * destination, then reveal it with the moving shell edge rather than reflowing its rows. */
@Composable
internal fun ComposerPanelBody(
    clock: PanelTravelClock?, requested: Boolean, returningDraft: Boolean,
    sideInset: Dp, expandedSide: Dp, bottomGap: Dp, dockGapChange: Dp, foldedInset: Dp,
    handleId: String, dismiss: () -> Unit, panel: (@Composable () -> Unit)?, draft: @Composable () -> Unit,
) {
    val currentClock by rememberUpdatedState(clock)
    val isRequested by rememberUpdatedState(requested)
    val pull = remember { PanelPull({ currentClock }, { isRequested }) }
    val bottom = WindowInsets.ime.union(WindowInsets.navigationBars)
    val bars = WindowInsets.statusBars
    val navigation = WindowInsets.navigationBars
    val prompt = remember { PanelPrompt { pull.progress() } }
    val measurer = rememberTextMeasurer()
    CompositionLocalProvider(LocalPanelPrompt provides prompt) {
        Layout(modifier = Modifier.drawPanelPrompt(prompt, measurer), content = {
            Box(Modifier.behindToolsPage(panel != null && !returningDraft)) {
                draft()
                if (panel != null) BlockPanelTouches(Modifier.matchParentSize())
            }
            if (panel != null) {
                Box(Modifier.fillMaxWidth().onPlaced { pull.root = it }.panelHandleDrag(handleId, pull, dismiss),
                    propagateMinConstraints = true) {
                    CompositionLocalProvider(LocalPanelPull provides pull, content = panel)
                }
                val dismissLabel = stringResource(R.string.chat_panel_drag_dismiss)
                Spacer(Modifier.size(HandleWidth, HandleHeight).testTag("chat-$handleId-handle")
                    .semantics { contentDescription = dismissLabel; this.dismiss { dismiss(); true } })
            }
        }) { children, limits ->
            val editor = children.first().measure(limits.copy(minHeight = 0))
            if (children.size == 1) {
                pull.editorHeight = editor.height
                return@Layout layout(editor.width, editor.height) { editor.place(0, 0) }
            }
            val p = clock?.progress ?: 0f
            val inset = bottom.getBottom(this)
            val windowHeight = limits.maxHeight + inset + bottomGap.roundToPx()
            val keyboardHeight = (inset - navigation.getBottom(this)).coerceAtLeast(0)
            // The cap is relative to the window. A tall list gives up space below its fixed
            // header when search opens the IME; a short list uses only its measured height.
            val windowCap = (windowHeight * .8f).roundToInt()
            val available = (limits.maxHeight - bars.getTop(this)).coerceAtLeast(0)
            val cap = (windowCap - keyboardHeight).coerceIn(available / 2, available).coerceAtMost(windowCap)
            val targetWidth = (limits.maxWidth + 2 * (sideInset.roundToPx() - expandedSide.roundToPx())).coerceAtLeast(0)
            val sheet = children[1].measure(Constraints(minWidth = targetWidth, maxWidth = targetWidth, maxHeight = cap))
            val handle = children[2].measure(Constraints(maxWidth = limits.maxWidth, maxHeight = sheet.height))
            pull.sourceScale = (1f - 2f * (foldedInset - expandedSide).toPx() / targetWidth.coerceAtLeast(1)).coerceIn(.65f, 1f)
            val sourceHeight = pull.editorHeight.takeIf { it > 0 } ?: editor.height
            pull.expandedHeight = sheet.height
            pull.dragTravel = (sheet.height - sourceHeight - dockGapChange.toPx()).coerceAtLeast(1f)
            val height = (sourceHeight + (sheet.height - sourceHeight) * p).roundToInt().coerceIn(0, limits.maxHeight)
            // After the shell-only overlap, the prompt travels with the upper rim.
            // The actual editable draft remains attached to the bottom throughout.
            prompt.shellGrowth = (sheet.height - sourceHeight) * (p - (clock?.contentStart ?: 0f)).coerceAtLeast(0f)
            pull.hiddenHeight = sheet.height - height
            layout(limits.maxWidth, height) {
                if (p < 1f) editor.place(0, height - editor.height)
                sheet.place((limits.maxWidth - sheet.width) / 2, 0)
                handle.place((limits.maxWidth - handle.width) / 2, 0)
            }
        }
    }
}

private data class PromptLabel(val text: String, val style: TextStyle, val color: Color)

private class PanelPrompt(val fraction: () -> Float) {
    var shellGrowth = 0f
    var root: LayoutCoordinates? = null
    var origin: LayoutCoordinates? = null
    var target: LayoutCoordinates? = null
    var editorLabel: PromptLabel? = null
    var targetLabel: PromptLabel? = null
}

private fun Modifier.drawPanelPrompt(prompt: PanelPrompt, measurer: TextMeasurer): Modifier =
    onPlaced { prompt.root = it }.drawWithContent {
        drawContent()
        val fraction = prompt.fraction()
        val root = prompt.root
        val origin = prompt.origin
        val target = prompt.target
        val label = prompt.targetLabel
        if (fraction > 0f && fraction < 1f && root?.isAttached == true && origin?.isAttached == true &&
            target?.isAttached == true && label != null) {
            val start = root.localPositionOf(origin, Offset.Zero) - Offset(0f, prompt.shellGrowth)
            val end = root.localPositionOf(target, Offset.Zero)
            val position = androidx.compose.ui.geometry.lerp(start, end, LinearOutSlowInEasing.transform(fraction))
            fun paint(text: PromptLabel, width: Int, alpha: Float) {
                val layout = measurer.measure(text.text, text.style, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    constraints = Constraints(maxWidth = width.coerceAtLeast(1)))
                drawText(layout, color = text.color, topLeft = position, alpha = alpha)
            }
            val mix = ((fraction - .12f) / .24f).coerceIn(0f, 1f)
            prompt.editorLabel?.let { if (mix < 1f) paint(it, origin.size.width, 1f - mix) }
            if (mix > 0f) paint(label, target.size.width, mix)
        }
    }

private val LocalPanelPrompt = compositionLocalOf<PanelPrompt?> { null }

@Composable
internal fun Modifier.panelPromptOrigin(): Modifier {
    val prompt = LocalPanelPrompt.current ?: return this
    return onPlaced { prompt.origin = it }
}

/** Preserve the real fields, metrics and semantics. During handoff, their labels share a
 * position in the shell's draw layer, outside the editor and search field's individual clips. */
@Composable
internal fun PanelPromptText(text: String, color: Color, style: TextStyle, modifier: Modifier = Modifier, fromEditor: Boolean = false) {
    val prompt = LocalPanelPrompt.current
    val label = remember(text, style, color) { PromptLabel(text, style, color) }
    DisposableEffect(prompt, label) {
        if (fromEditor) prompt?.editorLabel = label else prompt?.targetLabel = label
        onDispose {
            if (fromEditor && prompt?.editorLabel === label) prompt.editorLabel = null
            if (!fromEditor && prompt?.targetLabel === label) prompt.targetLabel = null
        }
    }
    Text(text, modifier.then(if (!fromEditor && prompt != null) Modifier.onPlaced { prompt.target = it } else Modifier)
        .drawWithContent {
            val f = prompt?.fraction?.invoke() ?: 0f
            if (f <= 0f || f >= 1f || prompt?.targetLabel == null) drawContent()
        }, color = color, style = style, maxLines = 1, overflow = TextOverflow.Ellipsis)
}

private class ListEdge { var viewport: LayoutCoordinates? = null }
private val LocalListEdge = compositionLocalOf<ListEdge?> { null }

/** Only scrolling content shares this lower edge. The header, search and provider rail stay stationary. */
@Composable
internal fun PanelViewport(modifier: Modifier, surface: Color, scrolledPx: () -> Int, content: @Composable () -> Unit) {
    val edge = remember { ListEdge() }
    val interactive = LocalPanelPull.current?.acceptingBodyInput() != false
    Box(modifier.onPlaced { edge.viewport = it }.drawWithContent {
        drawContent()
        val depth = 7.dp.toPx()
        val strength = (scrolledPx() / depth).coerceIn(0f, 1f)
        // Only fade pixels entering the fixed header. Empty padding keeps the exact shell color.
        if (strength > 0f) drawRect(Brush.verticalGradient(listOf(surface.copy(alpha = strength), surface.copy(alpha = 0f)),
            startY = 0f, endY = depth), size = Size(size.width, depth))
    }, propagateMinConstraints = true) {
        Box(Modifier.fillMaxWidth().behindToolsPage(!interactive), propagateMinConstraints = true) {
            CompositionLocalProvider(LocalListEdge provides edge, content = content)
        }
        if (!interactive) BlockPanelTouches(Modifier.matchParentSize())
    }
}

@Composable
private fun BlockPanelTouches(modifier: Modifier) {
    Spacer(modifier.pointerInput(Unit) {
        awaitPointerEventScope { while (true) awaitPointerEvent().changes.forEach { it.consume() } }
    })
}

private fun GraphicsLayerScope.panelItemAppearance(sourceScale: Float, depth: Float, reveal: Float, edge: Float = 1f) {
    val small = sourceScale - .12f * depth
    scaleX = (small + (1f - small) * reveal) * (.86f + .14f * edge)
    scaleY = scaleX
    alpha = reveal * edge
    transformOrigin = TransformOrigin(.5f, 0f)
}

/** The provider strip shares the items' scale and fade, with its own clip at the moving bottom. */
@Composable
internal fun PanelBottomRail(content: @Composable () -> Unit) {
    val pull = LocalPanelPull.current
    Box(Modifier.fillMaxWidth().offset { IntOffset(0, -(pull?.hiddenHeight ?: 0)) }.clipToBounds()) {
        Box(Modifier.fillMaxWidth().graphicsLayer {
            if (pull != null) {
                val progress = pull.progress()
                panelItemAppearance(pull.sourceScale, depth = 1f, reveal = progress)
                translationY = size.height * (1f - progress)
            }
        }.background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(bottom = 6.dp)) { content() }
    }
}

/** Sample the untransformed row slot. Its planar position, uniform scale and opacity
 * are functions of the same clock in both directions, including during a reversal. */
@Composable
internal fun PanelStep(modifier: Modifier = Modifier, inheritGroupPull: Boolean = false, content: @Composable () -> Unit) {
    val pull = LocalPanelPull.current
    if (pull == null) {
        Box(modifier) { content() }
        return
    }
    val bandPx = with(androidx.compose.ui.platform.LocalDensity.current) { 40.dp.toPx() }
    val listEdge = LocalListEdge.current
    var visibility by remember { mutableFloatStateOf(1f) }
    var top by remember { mutableFloatStateOf(0f) }
    var depth by remember { mutableFloatStateOf(0f) }
    Box(modifier.onGloballyPositioned { slot ->
        val viewport = listEdge?.viewport
        if (viewport != null && viewport.isAttached && slot.isAttached) {
            val y = viewport.localPositionOf(slot, Offset.Zero).y
            val band = minOf(bandPx, slot.size.height.toFloat()).coerceAtLeast(1f)
            visibility = ((viewport.size.height - y) / band).coerceIn(0f, 1f)
        }
        val root = pull.root
        if (!inheritGroupPull && root != null && root.isAttached && slot.isAttached) {
            top = root.localPositionOf(slot, Offset.Zero).y.coerceAtLeast(0f)
            depth = (top / root.size.height.coerceAtLeast(1)).coerceIn(0f, 1f)
        }
    }) {
        Box(Modifier.fillMaxWidth().graphicsLayer {
            val p = pull.progress()
            val start = .38f * depth
            val reveal = if (inheritGroupPull) 1f else ((p - start) / (1f - start)).coerceIn(0f, 1f)
            val edge = 1f - (1f - visibility) * p
            panelItemAppearance(pull.sourceScale, depth, reveal, edge)
            translationY = if (inheritGroupPull) 0f else (12.dp.toPx() - top * .72f) * (1f - p)
        }) { content() }
    }
}

/** Observe the original handle rectangle before its children. A tap in the shared
 * strip reaches the search field; only an actual handle drag consumes motion. */
@Composable
private fun Modifier.panelHandleDrag(id: String, pull: PanelPull, onDismiss: () -> Unit): Modifier {
    val close by rememberUpdatedState(onDismiss)
    return pointerInput(pull, id) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            if (down.position.y !in 0f..HandleHeight.toPx() ||
                abs(down.position.x - size.width * .5f) > HandleWidth.toPx() / 2) return@awaitEachGesture
            val motion = pull.clock() ?: return@awaitEachGesture
            if (!pull.requested() || !motion.atEnd) return@awaitEachGesture
            val anchor = pull.root ?: return@awaitEachGesture
            val start = anchor.localToRoot(down.position)
            val expanded = pull.expandedHeight.toFloat()
            val travel = pull.dragTravel
            val velocity = VelocityTracker()
            velocity.addPosition(down.uptimeMillis, start)
            var captured = false
            try {
                while (true) {
                    val point = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == down.id } ?: break
                    if (point.isConsumed || !anchor.isAttached || !pull.requested()) break
                    val position = anchor.localToRoot(point.position)
                    val delta = position - start
                    velocity.addPosition(point.uptimeMillis, position)
                    if (!captured) {
                        if (!point.pressed || abs(delta.x) > viewConfiguration.touchSlop && abs(delta.x) >= abs(delta.y)) break
                        if (delta.y <= viewConfiguration.touchSlop) continue
                        motion.hold()
                        captured = true
                    }
                    val distance = delta.y.coerceAtLeast(0f)
                    motion.seek(1f - distance / travel)
                    point.consume()
                    if (!point.pressed) {
                        if (distance > expanded * .5f || velocity.calculateVelocity().y > 700.dp.toPx()) close()
                        break
                    }
                }
            } finally {
                if (captured) motion.release()
            }
        }
    }
}

@Composable
private fun PanelGrip(id: String, onDismiss: () -> Unit) {
    val pull = LocalPanelPull.current
    val label = stringResource(R.string.chat_panel_drag_dismiss)
    val fallback = if (pull == null) Modifier.semantics { contentDescription = label; dismiss { onDismiss(); true } } else Modifier
    Box(Modifier.fillMaxWidth().then(fallback).graphicsLayer { alpha = pull?.progress() ?: 1f }.padding(vertical = 8.dp),
        contentAlignment = Alignment.Center) {
        val light = MaterialTheme.colorScheme.surface.luminance() > .5f
        val shape = RoundedCornerShape(2.dp)
        Spacer(Modifier.size(40.dp, 4.dp).testTag("chat-$id-grip").background(Color.White, shape)
            .then(if (light) Modifier.border(1.dp, MaterialTheme.colorScheme.outline, shape) else Modifier))
    }
}

@Composable
internal fun ToolPanelHeading(onBack: () -> Unit, countLabel: String) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        PanelGrip("tools", onBack)
        PanelStep(Modifier.fillMaxWidth().zIndex(1f)) {
            Box(Modifier.fillMaxWidth().heightIn(min = 34.dp).testTag("chat-tools-heading"), contentAlignment = Alignment.Center) {
                PanelPromptText(stringResource(R.string.chat_tools_title), LocalContentColor.current,
                    LocalTextStyle.current.copy(fontSize = 18.sp, fontWeight = FontWeight.Medium),
                    Modifier.testTag("chat-tools-title").semantics { heading(); stateDescription = countLabel })
            }
        }
    }
}

@Composable
internal fun PanelModelSearch(picker: ChatModelPickerState, enabled: Boolean, atFavorites: Boolean, onFavorites: (() -> Unit)?) {
    val hint = stringResource(R.string.chat_panel_model_search_hint)
    val colors = MaterialTheme.colorScheme
    var focused by remember { mutableStateOf(false) }
    var lastQuery by remember(picker) { mutableStateOf(picker.searchQuery) }
    val value = if (picker.modelListNavigation) picker.searchQuery else lastQuery
    if (picker.modelListNavigation) SideEffect { lastQuery = value }
    Column(Modifier.fillMaxWidth().testTag("chat-model-heading")) {
        PanelGrip("model", picker::systemBack)
        PanelStep(Modifier.fillMaxWidth().zIndex(1f)) {
            val shape = HubShapes.Small
            val dark = colors.onSurface.luminance() > colors.surfaceContainerHigh.luminance()
            val fill = lerp(colors.surfaceContainerHigh, Color.Black, if (dark) .16f else .03f)
            val line = when {
                focused -> lerp(colors.primary, colors.onSurface, .45f)
                dark -> Color(0xFF38393A)
                else -> colors.onSurface.copy(alpha = .46f)
            }
            Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp).behindToolsPage(!enabled)) {
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("chat-model-search-box")
                    .background(fill, shape).border(1.dp, line, shape),
                    verticalAlignment = Alignment.CenterVertically) {
                    BasicTextField(value, { edit ->
                        if (enabled && picker.modelListNavigation) {
                            if (isQuerySubmitEdit(value, edit)) picker.submitSearch() else picker.updateSearchQuery(edit.singleLineQuery())
                        }
                    }, Modifier.weight(1f).focusProperties { canFocus = enabled }.onFocusChanged { focused = it.isFocused }
                        .testTag("chat-model-search-field").semantics { contentDescription = hint },
                        readOnly = !enabled, singleLine = true, textStyle = MaterialTheme.typography.bodyLarge.copy(color = colors.onSurface),
                        cursorBrush = SolidColor(colors.primary), keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { if (enabled) picker.submitSearch() }),
                        decorationBox = { field ->
                            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.width(48.dp), contentAlignment = Alignment.Center) {
                                    Icon(Icons.Outlined.Search, null, Modifier.size(18.dp), tint = colors.onSurface.copy(alpha = .86f))
                                }
                                Box(Modifier.weight(1f).padding(end = if (onFavorites == null) 12.dp else 0.dp,
                                    top = 12.dp, bottom = 12.dp), contentAlignment = Alignment.CenterStart) {
                                    if (value.text.isEmpty()) PanelPromptText(hint, colors.onSurfaceVariant, MaterialTheme.typography.bodyLarge)
                                    field()
                                }
                            }
                        })
                    if (onFavorites != null) IconButton(onClick = onFavorites, enabled = enabled,
                        modifier = Modifier.size(48.dp).testTag("chat-model-bookmark").semantics { selected = atFavorites }) {
                        Icon(if (atFavorites) Icons.Filled.Bookmark else Icons.Outlined.BookmarkBorder, stringResource(R.string.browse_favorites),
                            Modifier.size(18.dp), tint = colors.onSurface.copy(alpha = if (atFavorites) 1f else .9f))
                    }
                }
                if (!enabled) BlockPanelTouches(Modifier.matchParentSize())
            }
        }
    }
}
