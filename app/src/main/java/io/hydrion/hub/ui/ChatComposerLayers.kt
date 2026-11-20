package io.hydrion.hub.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
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
import io.hydrion.hub.model.Draft
import io.hydrion.hub.ui.composer.ChatToolsPage
import io.hydrion.hub.ui.composer.ToolsPageState

private enum class WorkspaceLayer { Composer, Pages }

/** Measure the one live editor first, then place it above both pages in the same layout pass. */
@Composable
internal fun ChatComposerLayers(
    modifier: Modifier, composer: @Composable () -> Unit, pages: @Composable (bottomBarHeight: Dp) -> Unit,
) {
    SubcomposeLayout(modifier) { limits ->
        val bar = subcompose(WorkspaceLayer.Composer, composer).single()
            .measure(limits.copy(minWidth = 0, minHeight = 0))
        val page = subcompose(WorkspaceLayer.Pages) { Box { pages(bar.height.toDp()) } }
            .single().measure(limits)
        layout(page.width, page.height) {
            page.placeRelative(0, 0)
            bar.placeRelative(0, page.height - bar.height)
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
