package io.hydrion.hub.provider

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import io.hydrion.hub.R
import io.hydrion.hub.model.RemoteModel
import io.hydrion.hub.ui.LocalComposerActionInsets
import io.hydrion.hub.ui.theme.HubStyle
import io.hydrion.hub.ui.theme.composerActionFill
import io.hydrion.hub.ui.theme.composerActionInk

// The provider heading is pinned. The provider switch and model heading scroll with the results.
private const val FirstModelRow = 2
private const val ProviderRow = "provider"
private const val ModelsHeading = "models"
private const val EmptyRow = "empty"

private fun modelRowKey(providerId: String, modelId: String) = "model:${providerId.length}:$providerId/$modelId"

private fun menuRowKeys(providerId: String, models: List<ProviderModel>) = buildList {
    add(ProviderRow)
    add(ModelsHeading)
    if (models.isEmpty()) add(EmptyRow) else models.forEach { add(modelRowKey(providerId, it.id)) }
}

private fun LazyListState.anchor(): PickerScrollAnchor {
    val index = firstVisibleItemIndex
    val key = layoutInfo.visibleItemsInfo.firstOrNull { it.index == index }?.key as? String
    return PickerScrollAnchor(key, index, firstVisibleItemScrollOffset)
}

internal fun matchingMenuModels(models: List<ProviderModel>, query: String): List<ProviderModel> {
    val term = query.trim()
    if (term.isEmpty()) return models
    return models.filter {
        it.name.contains(term, ignoreCase = true) || it.id.contains(term, ignoreCase = true) ||
            it.profile.externalId.contains(term, ignoreCase = true)
    }
}

@Composable
internal fun ProviderModelsList(
    provider: Provider, models: List<ProviderModel>, active: RemoteModel,
    picker: ChatModelPickerState, dark: Boolean, paused: Boolean, hideForReturn: Boolean,
    onEditModel: (RemoteModel, PickerBrowseSnapshot) -> Unit, onPick: (ProviderModel) -> Unit,
) = key(provider.id) {
    val searching = picker.page == ChatModelPickerState.Page.Search
    val query = if (searching) picker.searchQuery.text.trim() else ""
    val matches = remember(models, query) { matchingMenuModels(models, query) }
    val nameCounts = remember(models) { models.groupingBy { it.name }.eachCount() }
    val browsingScroll = rememberLazyListState()
    val filteredScroll = rememberLazyListState()
    val scroll = if (query.isEmpty()) browsingScroll else filteredScroll
    val restore = picker.pendingBrowseRestore
    var editReturnHeight by remember(searching, query) { mutableStateOf<Dp?>(null) }
    val scrollRequest = picker.pendingSearchScroll
    LaunchedEffect(searching, query, scrollRequest, paused, restore) {
        // Reattaching a filtered list is not a search edit. In particular it must not undo a restored anchor.
        if (!searching || scrollRequest == null || paused || restore != null ||
            scrollRequest != picker.pendingSearchScroll || query != picker.searchQuery.text.trim()) return@LaunchedEffect
        val view = scroll.layoutInfo
        val groupFits = scrollRequest == ChatModelPickerState.SearchScroll.Filter && scroll.firstVisibleItemIndex == 0 &&
            view.visibleItemsInfo.any { it.index == FirstModelRow && it.offset + it.size <= view.viewportEndOffset }
        scroll.requestScrollToItem(if (query.isEmpty() || groupFits) 0 else FirstModelRow)
        picker.consumeSearchScroll()
    }
    LaunchedEffect(restore, hideForReturn, paused) {
        if (restore == null || (paused && !hideForReturn)) return@LaunchedEffect
        if (provider.id == restore.providerId) {
            editReturnHeight = restore.menuHeightDp.takeIf { it > 0 }?.dp
            val browsingIndex = restore.browsing.resolveIndex(menuRowKeys(provider.id, models))
            val filteredIndex = restore.filtered.resolveIndex(menuRowKeys(provider.id, matchingMenuModels(models, restore.query.text)))
            // Only the displayed state has a measured LazyColumn. Wait for its snap while the list is hidden.
            if (scroll === browsingScroll) {
                filteredScroll.requestScrollToItem(filteredIndex, restore.filtered.offset)
                browsingScroll.scrollToItem(browsingIndex, restore.browsing.offset)
            } else {
                browsingScroll.requestScrollToItem(browsingIndex, restore.browsing.offset)
                filteredScroll.scrollToItem(filteredIndex, restore.filtered.offset)
            }
        }
        picker.consumeBrowseRestore(restore, provider.id)
    }
    val actionLabel = stringResource(if (searching) R.string.chat_picker_search_close else R.string.chat_picker_search)
    val colors = MaterialTheme.colorScheme
    val headingColor = if (dark) Color(0xFFAFAFAF) else colors.onSurfaceVariant
    val modelNameColor = if (dark) Color(0xFFEDEDED) else Color.Unspecified
    val keepEditorFocus = Modifier.focusProperties { canFocus = false }
    val density = LocalDensity.current
    var measuredHeightPx by remember { mutableIntStateOf(0) }
    // The first remeasure must already use the saved viewport, before scrollToItem clamps against its end.
    val returnHeight = restore?.takeIf { it.providerId == provider.id && it.menuHeightDp > 0f }?.menuHeightDp?.dp
        ?: editReturnHeight ?: Dp.Infinity

    BoxWithConstraints(Modifier.fillMaxWidth().heightIn(max = returnHeight)
        .onSizeChanged { measuredHeightPx = it.height }.testTag("chat-model-list")) {
        // Beside a short list, the search button leaves its full height available for scrolling.
        val menuHeight = maxHeight
        val actionSpace = if (menuHeight < 152.dp) Modifier.padding(end = 52.dp)
            else Modifier.padding(bottom = 50.dp)
        Column(Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                PickerBackButton(stringResource(R.string.chat_picker_back_to_controls), picker::open,
                    Modifier.testTag("chat-model-list-back"))
                PickerHeading(stringResource(R.string.chat_picker_provider), headingColor)
            }
            LazyColumn(Modifier.fillMaxWidth().then(actionSpace).testTag("chat-model-results"), state = scroll) {
                item(key = ProviderRow) {
                    Row(keepEditorFocus.fillMaxWidth().testTag("chat-model-provider-trigger")
                        .clickable(role = Role.Button, onClick = picker::providers).heightIn(min = 48.dp)
                        .padding(horizontal = 14.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(provider.name, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null, Modifier.size(18.dp))
                    }
                }
                item(key = ModelsHeading) { PickerHeading(stringResource(R.string.chat_picker_models), headingColor) }
                if (matches.isEmpty()) item(key = EmptyRow) {
                    val empty = if (models.isEmpty()) R.string.chat_picker_provider_empty else R.string.chat_picker_search_empty
                    Text(stringResource(empty), Modifier.testTag("chat-model-search-empty")
                        .semantics { liveRegion = LiveRegionMode.Polite }.padding(horizontal = 14.dp, vertical = 8.dp),
                        style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                }
                items(matches, key = { modelRowKey(provider.id, it.id) }) { model ->
                    val route = (model to provider).route
                    val label = if (nameCounts[model.name] == 1) model.name else "${model.name} · ${model.id}"
                    PickerOption(label, active == route, Modifier.testTag("chat-model-option:${provider.id}/${model.id}"),
                        maxLines = 1, labelColor = modelNameColor, onEditModel = {
                            val page = picker.page
                            if (!paused && (page == ChatModelPickerState.Page.Models || page == ChatModelPickerState.Page.Search)) {
                                // Preserve the viewport too: growing it near the end would pull earlier rows into view.
                                val height = with(density) { measuredHeightPx.toDp() }
                                editReturnHeight = height
                                onEditModel(route, PickerBrowseSnapshot(page, provider.id, picker.searchQuery,
                                    browsingScroll.anchor(), filteredScroll.anchor(), height.value))
                            }
                        }) { onPick(model) }
                }
            }
        }
        // Keep the same independent control and hit rectangle in both states.
        val enabled = searching || models.isNotEmpty()
        // Dismissing search stays clickable with the neutral send colors.
        val accented = enabled && !searching
        val sendInsets = LocalComposerActionInsets.current
        val hitEnd = 4.dp
        val hitBottom = 2.dp
        Box(Modifier.align(Alignment.BottomEnd).padding(end = hitEnd, bottom = hitBottom).size(48.dp)
            .then(keepEditorFocus).clip(CircleShape).testTag(if (searching) "chat-model-search-close" else "chat-model-search")
            .semantics { contentDescription = actionLabel }
            .clickable(enabled = enabled, role = Role.Button) {
                if (searching) picker.back() else picker.search()
            }, contentAlignment = Alignment.Center) {
            // Keep the hit target and list clearance fixed. Move the face inside it to the
            // measured logical end/bottom insets; offset mirrors its x component in RTL.
            val placement = if (sendInsets == null) Modifier else Modifier.align(Alignment.BottomEnd).offset {
                IntOffset(hitEnd.roundToPx() - sendInsets.x, hitBottom.roundToPx() - sendInsets.y)
            }
            Box(placement.size(HubStyle.ComposerActionDiameter).testTag("chat-model-search-face")
                .background(colors.composerActionFill(accented), CircleShape),
                contentAlignment = Alignment.Center) {
                Icon(if (searching) Icons.Outlined.Close else Icons.Outlined.Search, null, Modifier.size(19.dp),
                    tint = colors.composerActionInk(accented))
            }
        }
    }
}
