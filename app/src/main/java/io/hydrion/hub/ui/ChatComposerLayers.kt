package io.hydrion.hub.ui

import androidx.compose.foundation.background
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
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
        val bounds = capsule?.translate(IntOffset(barLeft, barTop - safe.exclude(consumed).getTop(this)))
        val actionInsets = capsule?.let { bar.actionInsets(it, layoutDirection) }
        val overlay = subcompose(WorkspaceLayer.Picker) {
            CompositionLocalProvider(LocalPickerComposerBounds provides bounds, LocalComposerActionInsets provides actionInsets,
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
    val shield = Modifier.fillMaxSize().pointerInput(Unit) {
        awaitPointerEventScope { while (true) awaitPointerEvent().changes.forEach { it.consume() } }
    }
    // The shield covers the whole previous page, including the area the moving surface has not reached.
    Box(Modifier.fillMaxSize()) {
        Spacer(shield)
        Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top)).clipToBounds()) {
            Box(Modifier.fillMaxSize().graphicsLayer {
                translationY = (state.progress - 1f) * size.height
                alpha = state.progress
            }.testTag("chat-tools-surface").background(MaterialTheme.colorScheme.background).padding(bottom = composerHeight)
                .behindToolsPage(!state.interactive)) {
                ChatToolsPage(draft, state.query.text, onSearch, onImageTool)
            }
        }
        if (!state.interactive) Spacer(shield)
    }
}
