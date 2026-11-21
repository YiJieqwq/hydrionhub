package io.hydrion.hub.ui

import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import io.hydrion.hub.ui.chrome.ChatHeaderLayout
import io.hydrion.hub.ui.composer.ChatToolsPage
import io.hydrion.hub.ui.composer.ToolsPageState

private enum class WorkspaceLayer { Composer, Pages, Picker }

private typealias PageContent = @Composable (Dp, Dp, () -> Int) -> Unit

// SubcomposeLayout compares slot lambdas by identity. Reuse this slot while only the
// editor is changing height; passing a fresh wrapper would enter page composition again.
private class WorkspacePageSlot {
    var lastChatDockPx: Int? = null
    var excessDockPx by mutableIntStateOf(0)
    private val readExcess = { excessDockPx }
    private var timelineDock = Dp.Unspecified
    private var panelDock = Dp.Unspecified
    private var body: PageContent? = null
    private var slot: @Composable () -> Unit = {}

    fun content(timeline: Dp, panel: Dp, pages: PageContent): @Composable () -> Unit {
        if (timeline != timelineDock || panel != panelDock || pages !== body) {
            timelineDock = timeline
            panelDock = panel
            body = pages
            slot = { Box { pages(timeline, panel, readExcess) } }
        }
        return slot
    }
}

/** Measure the one live editor first; both the pages and the selector can use this frame's geometry. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ChatComposerLayers(
    modifier: Modifier, composer: @Composable () -> Unit, picker: (@Composable () -> Unit)?,
    trackPickerAnchor: () -> Boolean,
    freezeTimelineDock: Boolean = false, panelActive: Boolean = false, panelSettled: Boolean = false,
    dockKey: Any? = null,
    pages: @Composable (timelineDock: Dp, panelDock: Dp, excessDockPx: () -> Int) -> Unit,
) {
    val pageSlot = remember(dockKey) { WorkspacePageSlot() }
    val safe = WindowInsets.safeDrawing
    var consumed by remember { mutableStateOf(WindowInsets(0, 0, 0, 0)) }
    SubcomposeLayout(modifier.onConsumedWindowInsetsChanged { consumed = it }) { limits ->
        val width = minOf(limits.maxWidth, ChatHeaderLayout.MaxWidth.roundToPx())
        val contentLimits = limits.copy(minWidth = width, maxWidth = width)
        val bar = subcompose(WorkspaceLayer.Composer, composer).single()
            .measure(contentLimits.copy(minWidth = 0, minHeight = 0))
        if (!panelActive || pageSlot.lastChatDockPx == null) pageSlot.lastChatDockPx = bar.height
        val timelineDock = if (freezeTimelineDock) pageSlot.lastChatDockPx ?: bar.height else bar.height
        val panelDock = when {
            !panelActive -> 0
            panelSettled -> bar.height
            else -> bar.searchDockHeight()
        }
        pageSlot.excessDockPx = if (panelActive) bar.height - panelDock else 0
        val page = subcompose(WorkspaceLayer.Pages, pageSlot.content(timelineDock.toDp(), panelDock.toDp(), pages))
            .single().measure(contentLimits)
        val left = Alignment.CenterHorizontally.align(width, limits.maxWidth, layoutDirection)
        val barLeft = left + if (layoutDirection == LayoutDirection.Rtl) width - bar.width else 0
        val barTop = page.height - bar.height
        val pickerOffset = IntOffset(barLeft, barTop - safe.exclude(consumed).getTop(this))
        layout(limits.maxWidth, page.height) {
            page.place(left, 0)
            bar.place(barLeft, barTop)
            // Read alignment lines from the finally placed toolbar, including RTL siblings.
            // The picker is measured and placed in this same frame.
            if (picker != null) {
                val capsule = if (trackPickerAnchor()) bar.capsuleBounds() else null
                val bounds = capsule?.translate(pickerOffset)
                val label = capsule?.let { bar.pickerModelLabel()?.translate(pickerOffset) }
                val modelTravelX = if (capsule != null) bar.pickerModelTravelX() else 0
                subcompose(WorkspaceLayer.Picker) {
                    CompositionLocalProvider(LocalPickerComposerBounds provides bounds, LocalPickerModelLabelBounds provides label,
                        LocalPickerModelTravelX provides modelTravelX, content = picker)
                }.singleOrNull()?.measure(limits)?.place(0, 0)
            }
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
internal fun Modifier.behindToolsPage(covered: Boolean) = if (!covered) this else
    clearAndSetSemantics { }.focusProperties { enter = { FocusRequester.Cancel } }.focusGroup()

// Read the live difference during drawing. The transition shield owns input until the
// search dock has landed, when the full content is visible and list bounds match the original layout.
internal fun Modifier.clipSlidingPageContent(excessDockPx: () -> Int) = drawWithContent {
    val excess = excessDockPx()
    if (excess > 0) {
        clipRect(bottom = (size.height - excess).coerceAtLeast(0f)) { this@drawWithContent.drawContent() }
    } else drawContent()
}

@Composable
internal fun ToolsPageOverlay(
    state: ToolsPageState, search: String, imageTool: String, panelDock: Dp, excessDockPx: () -> Int,
    onSearch: (String) -> Unit, onImageTool: (String) -> Unit,
) {
    val active = state.active
    val travel = { state.progress }
    SlidingPageLayers(active, travel, state.interactive) {
        val surface = if (active) {
            slidingViewport()
                .then(slidingSurface(travel, state.interactive, "chat-tools-surface"))
                .padding(bottom = panelDock).clipSlidingPageContent(excessDockPx)
        } else Modifier
        RetainedToolOptions(active, surface) {
            ChatToolsPage(search, imageTool, state.query.text, onSearch, onImageTool)
        }
    }
}

/** Both tool-style pages sit above the chat but below the single live composer. */
@Composable
internal fun ChatSlidingPage(progress: () -> Float, interactive: Boolean, tag: String, content: @Composable () -> Unit) {
    SlidingPageLayers(true, progress, interactive) {
        Box(slidingViewport()) {
            Box(slidingSurface(progress, interactive, tag)) { content() }
        }
    }
}

@Composable
private fun slidingViewport() = Modifier.fillMaxSize()
    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top)).clipToBounds()

private fun slidingSurface(progress: () -> Float, interactive: Boolean, tag: String) = Modifier.fillMaxSize()
    .graphicsLayer {
        val travel = progress()
        translationY = (travel - 1f) * size.height
        alpha = travel
    }.testTag(tag).behindToolsPage(!interactive)

@Composable
private fun SlidingPageLayers(active: Boolean, progress: () -> Float, interactive: Boolean, content: @Composable () -> Unit) {
    Box(if (active) Modifier.fillMaxSize() else Modifier) {
        if (active) {
            SlidingPageShield()
            SlidingPageGlass(progress)
        }
        content()
        if (active && !interactive) SlidingPageShield()
    }
}

@Composable
private fun SlidingPageShield() {
    // Cover the previous page, including the area the moving surface has not reached.
    Spacer(Modifier.fillMaxSize().pointerInput(Unit) {
        awaitPointerEventScope { while (true) awaitPointerEvent().changes.forEach { it.consume() } }
    })
}

@Composable
private fun BoxScope.SlidingPageGlass(progress: () -> Float) {
    // Only the incoming controls are inset; shade the outgoing header across the status bar.
    val material = MaterialTheme.colorScheme.background.copy(alpha = SlidingPageBackdrop.BodyOpacity)
    Spacer(Modifier.matchParentSize().drawWithCache {
        val backdrop = SlidingPageBackdrop(size.height, 36.dp.toPx())
        val feather = if (backdrop.featherPx > 0f) Brush.verticalGradient(
            listOf(material, material.copy(alpha = 0f)), endY = backdrop.featherPx
        ) else null
        onDrawBehind {
            val solidBottom = backdrop.solidBottomPx(progress())
            val visibleBottom = minOf(size.height, solidBottom + backdrop.featherPx)
            if (solidBottom >= size.height) drawRect(material)
            else if (feather != null && visibleBottom > 0f) translate(top = solidBottom) {
                // One draw avoids a raster seam at the fractional-pixel edge.
                drawRect(feather, topLeft = Offset(0f, -solidBottom), size = Size(size.width, visibleBottom))
            }
        }
    })
}
