package io.hydrion.hub.ui

import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
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
import io.hydrion.hub.model.Draft
import io.hydrion.hub.ui.composer.ChatToolsPage
import io.hydrion.hub.ui.composer.ToolsPageState

private enum class WorkspaceLayer { Composer, Pages, Picker }

/** Measure the one live editor first; both the pages and the selector can use this frame's geometry. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ChatComposerLayers(
    modifier: Modifier, composer: @Composable () -> Unit, picker: @Composable () -> Unit,
    trackPickerAnchor: () -> Boolean,
    pages: @Composable (bottomBarHeight: Dp) -> Unit,
) {
    val safe = WindowInsets.safeDrawing
    var consumed by remember { mutableStateOf(WindowInsets(0, 0, 0, 0)) }
    SubcomposeLayout(modifier.onConsumedWindowInsetsChanged { consumed = it }) { limits ->
        val width = minOf(limits.maxWidth, ChatHeaderLayout.MaxWidth.roundToPx())
        val contentLimits = limits.copy(minWidth = width, maxWidth = width)
        val bar = subcompose(WorkspaceLayer.Composer, composer).single()
            .measure(contentLimits.copy(minWidth = 0, minHeight = 0))
        val page = subcompose(WorkspaceLayer.Pages) { Box { pages(bar.height.toDp()) } }
            .single().measure(contentLimits)
        val left = Alignment.CenterHorizontally.align(width, limits.maxWidth, layoutDirection)
        val barLeft = left + if (layoutDirection == LayoutDirection.Rtl) width - bar.width else 0
        val barTop = page.height - bar.height
        // The selector's own Box applies vertical safeDrawing padding. Its origin is the
        // workspace's left edge and its first unconsumed safe row, including in split screen.
        val capsule = if (trackPickerAnchor()) bar.capsuleBounds() else null
        val pickerOffset = IntOffset(barLeft, barTop - safe.exclude(consumed).getTop(this))
        val bounds = capsule?.translate(pickerOffset)
        val label = capsule?.let { bar.pickerModelLabel()?.translate(pickerOffset) }
        val modelTravelX = if (capsule != null) bar.pickerModelTravelX() else 0
        val overlay = subcompose(WorkspaceLayer.Picker) {
            CompositionLocalProvider(LocalPickerComposerBounds provides bounds, LocalPickerModelLabelBounds provides label,
                LocalPickerModelTravelX provides modelTravelX,
                content = picker)
        }.singleOrNull()?.measure(limits)
        layout(limits.maxWidth, page.height) {
            page.place(left, 0)
            bar.place(barLeft, barTop)
            overlay?.place(0, 0)
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
internal fun Modifier.behindToolsPage(covered: Boolean) = if (!covered) this else
    clearAndSetSemantics { }.focusProperties { enter = { FocusRequester.Cancel } }.focusGroup()

@Composable
internal fun ToolsPageOverlay(
    state: ToolsPageState, draft: Draft, composerHeight: Dp,
    onSearch: (String) -> Unit, onImageTool: (String) -> Unit,
) {
    ChatSlidingPage({ state.progress }, state.interactive, "chat-tools-surface") {
        Box(Modifier.fillMaxSize().padding(bottom = composerHeight)) {
            ChatToolsPage(draft, state.query.text, onSearch, onImageTool)
        }
    }
}

/** Both tool-style pages sit above the chat but below the single live composer. */
@Composable
internal fun ChatSlidingPage(progress: () -> Float, interactive: Boolean, tag: String, content: @Composable () -> Unit) {
    val shield = Modifier.fillMaxSize().pointerInput(Unit) {
        awaitPointerEventScope { while (true) awaitPointerEvent().changes.forEach { it.consume() } }
    }
    // The shield covers the whole previous page, including the area the moving surface has not reached.
    Box(Modifier.fillMaxSize()) {
        Spacer(shield)
        // The outgoing header can slide behind the status bar. Only the incoming controls
        // are inset; the glass must shade the app content on both sides of that boundary.
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
                    // The brush clamps to its first colour before the feather. One draw avoids
                    // a raster seam where the flat material meets its fractional-pixel edge.
                    drawRect(feather, topLeft = Offset(0f, -solidBottom), size = Size(size.width, visibleBottom))
                }
            }
        })
        Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top)).clipToBounds()) {
            Box(Modifier.fillMaxSize().graphicsLayer {
                val travel = progress()
                translationY = (travel - 1f) * size.height
                alpha = travel
            }.testTag(tag).behindToolsPage(!interactive)) {
                content()
            }
        }
        if (!interactive) Spacer(shield)
    }
}
