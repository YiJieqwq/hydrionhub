package io.hydrion.hub.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.union
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.IntRect
import io.hydrion.hub.model.Draft
import io.hydrion.hub.model.RemoteModel
import io.hydrion.hub.provider.ChatModelConfigScreen
import io.hydrion.hub.provider.PickerBrowseSnapshot
import io.hydrion.hub.provider.ProviderStore
import io.hydrion.hub.ui.composer.ModelEditorComposerSnapshot
import io.hydrion.hub.ui.messages.ChatEdgeFade

internal const val ModelEditorMotionMillis = ChatPanelMotionMillis

// The existing IME padding already supplies all downward movement for a visible source keyboard.
internal fun modelVisitDockOffset(cover: Float, dockHeight: Float, followsIme: Boolean): Float =
    if (followsIme) 0f else cover * dockHeight

internal fun modelVisitBrowseReveal(browse: Float, cover: Float): Float = browse * (1f - cover)

internal data class ModelEditVisit(
    val route: RemoteModel,
    val conversationId: String,
    val draft: Draft,
    val browse: PickerBrowseSnapshot,
    val editor: ModelEditorComposerSnapshot,
    val entryDockTravel: Float,
)

@Composable
internal fun ModelEditorOverlay(
    store: ProviderStore, visit: ModelEditVisit, returning: Boolean, cover: State<Float>,
    composerBounds: () -> IntRect?, onBack: () -> Unit,
) {
    val safe = WindowInsets.systemBars.union(WindowInsets.displayCutout)
    val background = MaterialTheme.colorScheme.background
    var origin by remember { mutableStateOf(Offset.Zero) }
    val showPage by remember(returning, cover) { derivedStateOf { !returning || cover.value > 0f } }
    // This sibling of the inset-aware chat uses the full window for both directions of travel.
    Box(Modifier.fillMaxSize().onGloballyPositioned { origin = it.positionInWindow() }) {
        val shield = Modifier.matchParentSize().pointerInput(Unit) {
            awaitPointerEventScope { while (true) awaitPointerEvent().changes.forEach { it.consume() } }
        }
        Spacer(shield)
        if (showPage) {
            // Keep the moving page, including its local scroll fade, inside the permanent system bars.
            Box(Modifier.fillMaxSize().drawWithContent {
                clipRect(safe.getLeft(this, layoutDirection).toFloat(), safe.getTop(this).toFloat(),
                    size.width - safe.getRight(this, layoutDirection), size.height - safe.getBottom(this)) {
                    this@drawWithContent.drawContent()
                }
            }.graphicsLayer {
                val progress = cover.value
                translationY = (progress - 1f) * size.height
                alpha = progress
            }.testTag("chat-model-editor")) {
                ChatModelConfigScreen(store, visit.route, returning, visit.editor.imeVisible, onBack)
            }
        }
        // Draw over the real workspace (including the composer), only OUTSIDE the moving page.
        // The page's own scroll-edge fade lives inside its one composited alpha layer.
        Spacer(Modifier.matchParentSize().drawWithCache {
            val clear = background.copy(alpha = 0f)
            val safeTop = safe.getTop(this).toFloat()
            val safeBottom = size.height - safe.getBottom(this)
            val left = safe.getLeft(this, layoutDirection).toFloat()
            val right = size.width - safe.getRight(this, layoutDirection)
            val bounds = composerBounds()
            val bands = modelEditorEdgeBands(safeTop, safeBottom, visit.entryDockTravel,
                bounds?.top?.minus(origin.y), bounds?.bottom?.minus(origin.y), density)
            val edge = background.copy(alpha = ChatEdgeFade.HeaderEdgeAlpha)
            val featherBrush = Brush.verticalGradient(listOf(clear, edge), endY = 1f)
            val topBrush = Brush.verticalGradient(0f to edge,
                ChatEdgeFade.TopTailStop to background.copy(alpha = ChatEdgeFade.TopTailAlpha), 1f to clear, endY = 1f)
            val bottomStops = buildList {
                add(0f to clear)
                if (bands.bottomStop > 0f) add(bands.bottomStop to background.copy(alpha = ChatEdgeFade.ComposerEdgeAlpha))
                if (bands.bottomEdgeStop > bands.bottomStop) add(bands.bottomEdgeStop to background.copy(alpha = .88f))
                if (bands.bottomEdgeStop < 1f) add(1f to background)
            }
            val bottomBrush = Brush.verticalGradient(*bottomStops.toTypedArray(), endY = 1f)
            onDrawBehind {
                val progress = cover.value
                val strength = progress
                if (strength <= 0f || safeBottom <= safeTop || right <= left) return@onDrawBehind
                // The translated full-height page ends at precisely height * cover.
                val start = exposedWorkspaceTop(size.height, safeTop, safeBottom, progress)
                clipRect(left, start, right, safeBottom) {
                    if (start < bands.topEnd) {
                        val depth = bands.topEnd - start
                        val full = bands.topEnd - safeTop
                        val opacity = strength * depth / full
                        // Avoid a hard leading edge where the page mask gives way to the workspace.
                        val featherEnd = start + depth * .25f * (start - safeTop) / full
                        paintEdgeBand(featherBrush, start, featherEnd, opacity)
                        paintEdgeBand(topBrush, featherEnd, bands.topEnd, opacity)
                    }
                    paintEdgeBand(bottomBrush, maxOf(start, bands.bottomStart), safeBottom, strength)
                }
            }
        })
        if (returning) {
            Spacer(shield)
            BackHandler { }
        }
    }
}

/** Draw a cached unit-height gradient into the visible portion of an edge. */
private fun DrawScope.paintEdgeBand(brush: Brush, top: Float, bottom: Float, alpha: Float) {
    if (bottom <= top) return
    translate(top = top) {
        scale(1f, bottom - top, Offset.Zero) { drawRect(brush, size = Size(size.width, 1f), alpha = alpha) }
    }
}
