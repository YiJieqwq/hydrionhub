package io.hydrion.hub.provider

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.LocalDensity
import kotlinx.coroutines.flow.first
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ChatModelConfigScreen(store: ProviderStore, route: RemoteModel, returning: Boolean, sourceHadIme: Boolean, onBack: () -> Unit) {
    val catalog by store.catalog.collectAsState()
    val provider = catalog.providers.singleOrNull { it.id == route.providerId }
    val model = provider?.models?.singleOrNull { it.id == route.modelId }
    if (provider == null || model == null) {
        LaunchedEffect(route) { onBack() }
        return
    }

    val origin = provider.credentialOrigin()
    key(route, origin) {
        val draft = remember {
            ModelEditDraft(model, provider.mode, catalog.defaults[provider.mode] == route)
        }
        val density = LocalDensity.current
        val ime = WindowInsets.ime
        val nav = WindowInsets.navigationBars
        val visible = androidx.compose.runtime.rememberUpdatedState(WindowInsets.isImeVisible)
        var useFormIme by remember { mutableStateOf(!sourceHadIme) }
        LaunchedEffect(Unit) {
            if (!useFormIme) {
                snapshotFlow { !visible.value && ime.getBottom(density) <= nav.getBottom(density) }.first { it }
                useFormIme = true
            }
        }
        // Only a keyboard opened for this form can reduce its content area. Freeze that
        // bottom inset on exit so the restored search keyboard cannot reflow the outgoing form.
        val exitInset = remember(returning) { if (returning && useFormIme) ime.getBottom(density) else 0 }
        val formIme = if (useFormIme && !returning) ime else WindowInsets(bottom = exitInset)
        val formInsets = WindowInsets.systemBars.union(WindowInsets.displayCutout).union(formIme)
        ProviderTheme {
            Surface(Modifier.fillMaxSize().testTag("chat-model-config"), color = MaterialTheme.colorScheme.background) {
                Column(Modifier.fillMaxSize().windowInsetsPadding(formInsets)
                    .onFocusChanged { if (it.hasFocus) useFormIme = true }.focusGroup()
                    .testTag("chat-model-form").padding(horizontal = 16.dp)) {
                    ModelEditor(draft, exists = true, otherModels = provider.models, onBack = onBack,
                        onSave = { edited, makeDefault ->
                            store.editModel(route, edited, origin, makeDefault && !draft.currentDefault)
                            onBack()
                        },
                        onDelete = { store.editModel(route, null, origin); onBack() }, fadeScrollEdges = true)
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
