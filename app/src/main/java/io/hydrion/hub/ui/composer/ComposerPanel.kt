package io.hydrion.hub.ui.composer

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.dismiss
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
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
import kotlin.math.roundToInt

private class PanelPull(val progress: () -> Float, val acceptingBodyInput: () -> Boolean) {
    var root: LayoutCoordinates? = null
    var hiddenHeight by mutableIntStateOf(0)
    var sourceScale by mutableFloatStateOf(1f)
}

private val LocalPanelPull = compositionLocalOf<PanelPull?> { null }

/** Both children live inside the composer's original Surface. Measure the list at its
 * destination, then reveal it with the moving shell edge rather than reflowing its rows. */
@Composable
internal fun ComposerPanelBody(
    progress: () -> Float, requested: Boolean, sideInset: Dp, expandedSide: Dp, bottomGap: Dp, foldedInset: Dp,
    panel: (@Composable () -> Unit)?, draft: @Composable () -> Unit,
) {
    val latest by rememberUpdatedState(progress)
    val isRequested by rememberUpdatedState(requested)
    val pull = remember { PanelPull({ latest() }, { isRequested && latest() >= 1f }) }
    val bottom = WindowInsets.ime.union(WindowInsets.navigationBars)
    val bars = WindowInsets.statusBars
    val navigation = WindowInsets.navigationBars
    // The source and the original animation clock survive a mid-flight Back.
    val source = remember { PanelSource() }
    Layout(content = {
        Box(Modifier.behindToolsPage(panel != null)) {
            draft()
            if (panel != null) BlockPanelTouches(Modifier.matchParentSize())
        }
        if (panel != null) {
            Box(Modifier.fillMaxWidth().onPlaced { pull.root = it }, propagateMinConstraints = true) {
                CompositionLocalProvider(LocalPanelPull provides pull, content = panel)
            }
        }
    }) { children, limits ->
        val editor = children.first().measure(limits.copy(minHeight = 0))
        if (children.size == 1) {
            source.height = editor.height
            source.ime = bottom.getBottom(this)
            source.release = null
            return@Layout layout(editor.width, editor.height) { editor.place(0, 0) }
        }
        val p = pull.progress().coerceIn(0f, 1f)
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
        pull.sourceScale = (1f - 2f * (foldedInset - expandedSide).toPx() / targetWidth.coerceAtLeast(1)).coerceIn(.65f, 1f)
        val sourceHeight = source.height.takeIf { it > 0 } ?: editor.height
        // When the entry IME retreats, the bottom follows it but the leading edge
        // still rises from the actual invocation point. Release this offset on Back.
        val entryOffset = sourceHeight - editor.height + (source.ime - inset).coerceAtLeast(0)
        if (requested) source.release = null
        else if (source.release == null) source.release = p to entryOffset
        val lift = source.release?.let { (at, px) -> if (at > 0f) px * (p / at).coerceIn(0f, 1f) else 0f } ?: entryOffset.toFloat()
        val height = ((editor.height + lift) * (1f - p) + sheet.height * p).roundToInt().coerceIn(0, limits.maxHeight)
        pull.hiddenHeight = sheet.height - height
        layout(limits.maxWidth, height) {
            if (p < 1f) editor.place(0, height - editor.height)
            sheet.place((limits.maxWidth - sheet.width) / 2, 0)
        }
    }
}

private class PanelSource {
    var height = 0
    var ime = 0
    var release: Pair<Float, Int>? = null
}

private class ListEdge { var viewport: LayoutCoordinates? = null }
private val LocalListEdge = compositionLocalOf<ListEdge?> { null }

/** Only scrolling content shares this lower edge. The header, search and provider rail stay stationary. */
@Composable
internal fun PanelViewport(modifier: Modifier, content: @Composable () -> Unit) {
    val edge = remember { ListEdge() }
    val interactive = LocalPanelPull.current?.acceptingBodyInput?.invoke() != false
    Box(modifier.onPlaced { edge.viewport = it }, propagateMinConstraints = true) {
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

/** The provider strip travels one strip height through the current lower edge.
 * Its layout slot still reserves room below the list, including when the list is short. */
@Composable
internal fun PanelBottomRail(content: @Composable () -> Unit) {
    val pull = LocalPanelPull.current
    Box(Modifier.fillMaxWidth().offset { IntOffset(0, -(pull?.hiddenHeight ?: 0)) }.clipToBounds()) {
        Box(Modifier.fillMaxWidth().graphicsLayer { translationY = size.height * (1f - (pull?.progress?.invoke() ?: 1f)) }
            .background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(bottom = 6.dp)) { content() }
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
            val reveal = if (inheritGroupPull) 1f else ((p - .48f * depth) / .52f).coerceIn(0f, 1f)
            val edge = 1f - (1f - visibility) * p
            alpha = reveal * edge
            val small = pull.sourceScale - .12f * depth
            scaleX = (small + (1f - small) * reveal) * (.86f + .14f * edge)
            scaleY = scaleX
            transformOrigin = TransformOrigin(.5f, 0f)
            translationY = if (inheritGroupPull) 0f else (12.dp.toPx() - top * .72f) * (1f - p)
        }) { content() }
    }
}

/** Feather only the stationary header's lower edge, leaving the composer's lower edge clear. */
@Composable
private fun PanelHeader(content: @Composable () -> Unit) {
    val tint = with(MaterialTheme.colorScheme) { lerp(surfaceContainerHigh, onSurface, .10f) }
    val pull = LocalPanelPull.current
    PanelStep(Modifier.fillMaxWidth().zIndex(1f).drawWithContent {
        drawContent()
        val depth = 7.dp.toPx()
        drawRect(Brush.verticalGradient(listOf(tint.copy(alpha = .55f * (pull?.progress?.invoke() ?: 1f)), tint.copy(alpha = 0f)),
            startY = size.height, endY = size.height + depth), topLeft = Offset(0f, size.height), size = Size(size.width, depth))
    }, content = content)
}

/** Only the handle owns vertical drags. List, bookmark and text gestures stay with their own controls. */
@Composable
private fun PanelHandle(id: String, onDismiss: () -> Unit) {
    val label = stringResource(R.string.chat_panel_drag_dismiss)
    val threshold = with(LocalDensity.current) { 28.dp.toPx() }
    var travel by remember { mutableFloatStateOf(0f) }
    val close by rememberUpdatedState(onDismiss)
    val shape = RoundedCornerShape(2.dp)
    Box(Modifier.fillMaxWidth().height(32.dp).testTag("chat-$id-handle")
        .draggable(rememberDraggableState { travel += it }, Orientation.Vertical,
            onDragStarted = { travel = 0f }, onDragStopped = { if (travel >= threshold) close() })
        .semantics { contentDescription = label; dismiss { close(); true } }, contentAlignment = Alignment.Center) {
        val light = MaterialTheme.colorScheme.surface.luminance() > .5f
        Spacer(Modifier.size(40.dp, 4.dp).testTag("chat-$id-grip").background(Color.White, shape)
            .then(if (light) Modifier.border(1.dp, MaterialTheme.colorScheme.outline, shape) else Modifier))
    }
}

@Composable
internal fun ToolPanelHeading(onBack: () -> Unit, countLabel: String) {
    PanelHeader {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            PanelHandle("tools", onBack)
            Box(Modifier.fillMaxWidth().heightIn(min = 34.dp).testTag("chat-tools-heading"), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.chat_tools_title), Modifier.testTag("chat-tools-title")
                    .semantics { heading(); stateDescription = countLabel },
                    fontSize = 18.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
internal fun PanelModelSearch(picker: ChatModelPickerState, enabled: Boolean, onFavorites: (() -> Unit)?) {
    val hint = stringResource(R.string.chat_panel_model_search_hint)
    val colors = MaterialTheme.colorScheme
    var focused by remember { mutableStateOf(false) }
    var lastQuery by remember(picker) { mutableStateOf(picker.searchQuery) }
    val value = if (picker.modelListNavigation) picker.searchQuery else lastQuery
    if (picker.modelListNavigation) SideEffect { lastQuery = value }
    PanelHeader {
        Column(Modifier.fillMaxWidth().testTag("chat-model-heading")) {
            PanelHandle("model", picker::systemBack)
            val shape = HubShapes.Small
            val line = if (focused) colors.primary.copy(alpha = .5f) else colors.outlineVariant.copy(alpha = .4f)
            Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp).behindToolsPage(!enabled)) {
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("chat-model-search-box")
                    .background(colors.surfaceContainerHighest, shape).border(1.dp, line, shape),
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
                                    Icon(Icons.Outlined.Search, null, Modifier.size(18.dp), tint = colors.onSurface.copy(alpha = .6f))
                                }
                                Box(Modifier.weight(1f).padding(end = if (onFavorites == null) 12.dp else 0.dp,
                                    top = 12.dp, bottom = 12.dp), contentAlignment = Alignment.CenterStart) {
                                    if (value.text.isEmpty()) Text(hint, color = colors.onSurfaceVariant,
                                        style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    field()
                                }
                            }
                        })
                    if (onFavorites != null) IconButton(onClick = onFavorites, enabled = enabled,
                        modifier = Modifier.size(48.dp).testTag("chat-model-bookmark")) {
                        Icon(Icons.Outlined.BookmarkBorder, stringResource(R.string.browse_favorites),
                            Modifier.size(18.dp), tint = colors.onSurface.copy(alpha = .7f))
                    }
                }
                if (!enabled) BlockPanelTouches(Modifier.matchParentSize())
            }
        }
    }
}
