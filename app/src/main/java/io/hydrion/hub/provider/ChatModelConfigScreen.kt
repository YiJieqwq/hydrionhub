package io.hydrion.hub.provider

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import io.hydrion.hub.model.RemoteModel
import io.hydrion.hub.ui.messages.ChatEdgeFade
import io.hydrion.hub.ui.theme.ProviderTheme

@Composable
internal fun ChatModelConfigScreen(store: ProviderStore, route: RemoteModel, onBack: () -> Unit) {
    val catalog by store.catalog.collectAsState()
    val provider = catalog.providers.singleOrNull { it.id == route.providerId }
    val model = provider?.models?.singleOrNull { it.id == route.modelId }
    if (provider == null || model == null) {
        LaunchedEffect(route) { onBack() }
        return
    }

    key(route) {
        val draft = remember {
            ModelEditDraft(model, provider.mode, catalog.defaults[provider.mode] == route)
        }
        ProviderTheme {
            Surface(Modifier.fillMaxSize().testTag("chat-model-config"), color = MaterialTheme.colorScheme.background) {
                Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding().padding(horizontal = 16.dp)) {
                    ModelEditor(draft, exists = true, otherModels = provider.models, onBack = onBack,
                        onSave = { edited, makeDefault ->
                            store.editModel(route, edited, makeDefault && !draft.currentDefault)
                            onBack()
                        },
                        onDelete = { store.editModel(route, null); onBack() }, fadeScrollEdges = true)
                }
            }
        }
    }
}

internal fun Modifier.modelConfigEdgeFades(scroll: ScrollState, background: Color) = drawWithCache {
    val topReach = ChatEdgeFade.BelowHeaderDp.dp.toPx()
    val bottomReach = ChatEdgeFade.AboveComposerDp.dp.toPx()
    val scale = ChatEdgeFade.scaleToViewport(size.height, topReach, bottomReach, density)
    if (scale == 0f) return@drawWithCache onDrawWithContent { drawContent() }
    val top = topReach * scale
    val bottom = bottomReach * scale
    val bottomStart = size.height - bottom
    val clear = background.copy(alpha = 0f)
    val topBrush = Brush.verticalGradient(
        0f to background.copy(alpha = ChatEdgeFade.HeaderEdgeAlpha),
        ChatEdgeFade.TopTailStop to background.copy(alpha = ChatEdgeFade.TopTailAlpha),
        1f to clear, endY = top,
    )
    val bottomBrush = Brush.verticalGradient(0f to clear, 1f to background.copy(alpha = ChatEdgeFade.ComposerEdgeAlpha),
        startY = bottomStart, endY = size.height)
    val reveal = ChatEdgeFade.ScrollRevealDp.dp.toPx()
    onDrawWithContent {
        drawContent()
        if (scroll.canScrollBackward) {
            drawRect(topBrush, size = Size(size.width, top), alpha = (scroll.value / reveal).coerceIn(0f, 1f))
        }
        if (scroll.canScrollForward) {
            val remaining = scroll.maxValue - scroll.value
            drawRect(bottomBrush, Offset(0f, bottomStart), Size(size.width, bottom),
                alpha = (remaining / reveal).coerceIn(0f, 1f))
        }
    }
}
