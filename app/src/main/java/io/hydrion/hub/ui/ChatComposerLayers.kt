package io.hydrion.hub.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.util.lerp
import io.hydrion.hub.ui.chrome.ChatHeaderLayout
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.withTimeoutOrNull

private enum class WorkspaceLayer { Composer, Pages, Picker, PanelDismiss }

/** Hold the list's bottom in workspace coordinates, including when an IME resizes the window. */
private class TimelineDockReturn(private val originalBottom: Int, private val originalIme: Int) {
    private var liveBottom by mutableIntStateOf(-1)
    private val release = Animatable(0f)

    fun measure(editorTop: Int): Int {
        liveBottom = editorTop
        return lerp(originalBottom, editorTop, release.value)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    suspend fun awaitEditor(ime: WindowInsets, target: WindowInsets, navigation: WindowInsets, density: Density) {
        // A show request or a nonzero inset can precede the final measured editor by many frames.
        // Let a moving IME finish. An idle, cancelled or differently sized IME must release the dock.
        withTimeoutOrNull(3_000) {
            snapshotFlow {
                val end = maxOf(originalIme + navigation.getBottom(density), target.getBottom(density))
                (end - ime.getBottom(density)) to liveBottom
            }.transformLatest { (remaining, bottom) ->
                if (remaining <= 0 && bottom == originalBottom) withFrameNanos { } else delay(1_000)
                emit(Unit)
            }.first()
        }
        if (liveBottom >= 0 && liveBottom != originalBottom) release.animateTo(1f, tween(150))
    }
}

private class WorkspacePageSlot {
    var lastTimelineBottomPx: Int? = null
    var anchor: IntRect? = null
    var modelLabel: IntRect? = null
    var labelTravel = 0
    private var timelineDock = Dp.Unspecified
    private var body: (@Composable (Dp) -> Unit)? = null
    private var slot: @Composable () -> Unit = {}

    fun content(timeline: Dp, pages: @Composable (Dp) -> Unit): @Composable () -> Unit {
        if (timeline != timelineDock || pages !== body) {
            timelineDock = timeline
            body = pages
            slot = { Box { pages(timeline) } }
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
    panelDismissLayer: (@Composable () -> Unit)? = null,
    panelEntryImePx: Int? = null,
    dockKey: Any? = null,
    pages: @Composable (timelineDock: Dp) -> Unit,
) {
    val pageSlot = remember(dockKey) { WorkspacePageSlot() }
    val covered = panelEntryImePx != null
    var dockReturn by remember(pageSlot) { mutableStateOf<TimelineDockReturn?>(null) }
    val ime = WindowInsets.ime
    val target = WindowInsets.imeAnimationTarget
    val navigation = WindowInsets.navigationBars
    val density = LocalDensity.current
    LaunchedEffect(pageSlot, panelEntryImePx) {
        if (panelEntryImePx != null) {
            val bottom = pageSlot.lastTimelineBottomPx
            dockReturn = if (panelEntryImePx > 0 && bottom != null) TimelineDockReturn(bottom, panelEntryImePx) else null
        } else {
            dockReturn?.awaitEditor(ime, target, navigation, density)
            dockReturn = null
        }
    }
    val safe = WindowInsets.safeDrawing
    var consumed by remember { mutableStateOf(WindowInsets(0, 0, 0, 0)) }
    SubcomposeLayout(modifier.onConsumedWindowInsetsChanged { consumed = it }) { limits ->
        // Opening the picker needs a measure pass before placement reads the capsule's alignment lines.
        val needsAnchor = picker != null && trackPickerAnchor()
        val width = minOf(limits.maxWidth, ChatHeaderLayout.MaxWidth.roundToPx())
        val contentLimits = limits.copy(minWidth = width, maxWidth = width)
        val bar = subcompose(WorkspaceLayer.Composer, composer).single()
            .measure(contentLimits.copy(minWidth = 0, minHeight = 0))
        val editorTop = limits.maxHeight - bar.height
        val timelineBottom = if (covered) pageSlot.lastTimelineBottomPx ?: editorTop else dockReturn?.measure(editorTop) ?: editorTop
        val timelineDock = (limits.maxHeight - timelineBottom).coerceIn(0, limits.maxHeight)
        pageSlot.lastTimelineBottomPx = timelineBottom
        val page = subcompose(WorkspaceLayer.Pages, pageSlot.content(timelineDock.toDp(), pages))
            .single().measure(contentLimits)
        val left = Alignment.CenterHorizontally.align(width, limits.maxWidth, layoutDirection)
        val barLeft = left + if (layoutDirection == LayoutDirection.Rtl) width - bar.width else 0
        val barTop = page.height - bar.height
        val pickerOffset = IntOffset(barLeft, barTop - safe.exclude(consumed).getTop(this))
        layout(limits.maxWidth, page.height) {
            page.place(left, 0)
            if (!covered) bar.place(barLeft, barTop)
            // Read alignment lines from the finally placed toolbar, including RTL siblings.
            // The picker is measured and placed in this same frame.
            if (picker != null) {
                // During the handoff the effort popup retires towards the original model chip.
                // The growing panel has no model chip and cannot serve as its anchor.
                if (!covered && needsAnchor) {
                    pageSlot.anchor = bar.capsuleBounds()?.translate(pickerOffset)
                    pageSlot.modelLabel = bar.pickerModelLabel()?.translate(pickerOffset)
                    pageSlot.labelTravel = bar.pickerModelTravelX()
                }
                val anchor = pageSlot.anchor.takeIf { needsAnchor }
                val label = pageSlot.modelLabel.takeIf { needsAnchor }
                val travel = pageSlot.labelTravel
                subcompose(WorkspaceLayer.Picker) {
                    CompositionLocalProvider(LocalPickerComposerBounds provides anchor, LocalPickerModelLabelBounds provides label,
                        LocalPickerModelTravelX provides travel, content = picker)
                }.singleOrNull()?.measure(limits)?.place(0, 0)
            }
            if (panelDismissLayer != null) {
                // The backdrop also owns taps in the gutters beyond a tablet's centered chat column.
                subcompose(WorkspaceLayer.PanelDismiss, panelDismissLayer).single().measure(limits).place(0, 0)
            }
            if (covered) bar.place(barLeft, barTop)
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
internal fun Modifier.behindToolsPage(covered: Boolean) = if (!covered) this else
    clearAndSetSemantics { }.focusProperties { enter = { FocusRequester.Cancel } }.focusGroup()
