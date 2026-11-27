package io.hydrion.hub.ui.composer

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.focus.focusProperties
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
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.hydrion.hub.R
import io.hydrion.hub.provider.ChatModelPickerState
import io.hydrion.hub.ui.behindToolsPage
import kotlin.math.roundToInt

private class PanelPull(val progress: () -> Float, val acceptingBodyInput: () -> Boolean) {
    var root: LayoutCoordinates? = null
    var sourceScale by mutableFloatStateOf(1f)
}

private val LocalPanelPull = compositionLocalOf<PanelPull?> { null }

/** Both children live inside the composer's original Surface. Measure the list at its
 * destination, then reveal it with the moving shell edge rather than reflowing its rows. */
@Composable
internal fun ComposerPanelBody(
    progress: () -> Float, models: Boolean, requested: Boolean, sideInset: Dp, foldedInset: Dp,
    panel: (@Composable () -> Unit)?, draft: @Composable () -> Unit,
) {
    val latest by rememberUpdatedState(progress)
    val isRequested by rememberUpdatedState(requested)
    val pull = remember { PanelPull({ latest() }, { isRequested && latest() >= 1f }) }
    val bottom = WindowInsets.ime.union(WindowInsets.navigationBars)
    val bars = WindowInsets.statusBars
    // The source and the original animation clock survive a mid-flight Back.
    val source = remember { PanelSource() }
    Layout(content = {
        Box(Modifier.behindToolsPage(panel != null)) {
            draft()
            if (panel != null) BlockPanelTouches(Modifier.matchParentSize())
        }
        if (panel != null) {
            Box(Modifier.fillMaxSize().onPlaced { pull.root = it }, propagateMinConstraints = true) {
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
        val full = limits.maxHeight + inset
        val margin = maxOf(bars.getTop(this) + 56.dp.roundToPx(), (full * if (models) .19f else .25f).roundToInt())
            .coerceAtMost(limits.maxHeight / 2)
        val end = (limits.maxHeight - margin).coerceAtLeast(1)
        val sheet = children[1].measure(Constraints.fixed(limits.maxWidth, end))
        pull.sourceScale = (1f - 2f * (foldedInset - sideInset).toPx() / limits.maxWidth.coerceAtLeast(1)).coerceIn(.65f, 1f)
        val sourceHeight = source.height.takeIf { it > 0 } ?: editor.height
        // When the entry IME retreats, the bottom follows it but the leading edge
        // still rises from the actual invocation point. Release this offset on Back.
        val entryOffset = sourceHeight - editor.height + (source.ime - inset).coerceAtLeast(0)
        if (requested) source.release = null
        else if (source.release == null) source.release = p to entryOffset
        val lift = source.release?.let { (at, px) -> if (at > 0f) px * (p / at).coerceIn(0f, 1f) else 0f } ?: entryOffset.toFloat()
        val height = ((editor.height + lift) * (1f - p) + end * p).roundToInt().coerceIn(0, limits.maxHeight)
        layout(limits.maxWidth, height) {
            if (p < .45f) editor.placeWithLayer(0, height - editor.height) { alpha = (1f - p / .45f).coerceIn(0f, 1f) }
            sheet.place(0, 0)
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
        Box(Modifier.fillMaxSize().behindToolsPage(!interactive), propagateMinConstraints = true) {
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

@Composable
internal fun PanelHeading(models: Boolean, onBack: () -> Unit, trailing: @Composable () -> Unit = {}) {
    val title = stringResource(if (models) R.string.chat_picker_models else R.string.chat_tools_title)
    val tint = with(MaterialTheme.colorScheme) { lerp(surfaceContainerHigh, onSurface, .10f) }
    val pull = LocalPanelPull.current
    PanelStep(Modifier.fillMaxWidth().zIndex(1f).drawWithContent {
        drawContent()
        val depth = 7.dp.toPx()
        drawRect(Brush.verticalGradient(listOf(tint.copy(alpha = .55f * (pull?.progress?.invoke() ?: 1f)), tint.copy(alpha = 0f)),
            startY = size.height, endY = size.height + depth), topLeft = Offset(0f, size.height), size = Size(size.width, depth))
    }) {
        Box(Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag(if (models) "chat-model-heading" else "chat-tools-heading")) {
            IconButton(onBack, Modifier.align(Alignment.CenterStart).testTag(if (models) "chat-model-list-back" else "chat-tools-back")) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.chat_tools_back))
            }
            Text(title, Modifier.align(Alignment.Center).padding(horizontal = 48.dp)
                .testTag(if (models) "chat-model-title" else "chat-tools-title").semantics { heading() },
                fontSize = 18.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Box(Modifier.align(Alignment.CenterEnd)) { trailing() }
        }
    }
}

@Composable
internal fun PanelModelSearch(picker: ChatModelPickerState, enabled: Boolean) {
    PanelStep(Modifier.fillMaxWidth()) {
        val hint = stringResource(R.string.chat_picker_search_hint)
        var lastQuery by remember(picker) { mutableStateOf(picker.searchQuery) }
        val value = if (picker.modelListNavigation) picker.searchQuery else lastQuery
        if (picker.modelListNavigation) SideEffect { lastQuery = value }
        Box(Modifier.fillMaxWidth().behindToolsPage(!enabled)) {
            OutlinedTextField(value, { edit ->
                if (enabled && picker.modelListNavigation) {
                    if (isQuerySubmitEdit(value, edit)) picker.submitSearch() else picker.updateSearchQuery(edit.singleLineQuery())
                }
            }, Modifier.fillMaxWidth().focusProperties { canFocus = enabled }.testTag("chat-model-search-field"),
                readOnly = !enabled, singleLine = true, shape = MaterialTheme.shapes.large,
                placeholder = { Text(hint, maxLines = 1) }, leadingIcon = { Icon(Icons.Outlined.Search, null) },
                trailingIcon = { if (value.text.isNotEmpty()) {
                    IconButton({ if (enabled) picker.clearSearchQuery() }, Modifier.testTag("chat-model-search-clear")) {
                        Icon(Icons.Outlined.Close, stringResource(R.string.chat_tools_clear))
                    }
                } }, keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { if (enabled) picker.submitSearch() }))
            if (!enabled) BlockPanelTouches(Modifier.matchParentSize())
        }
    }
}
