package io.hydrion.hub.provider

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.hydrion.hub.R
import io.hydrion.hub.model.Mode
import io.hydrion.hub.model.RemoteModel

// Keep the provider heading and switch in one item so model keys and scroll indices stay stable.
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
internal fun ChatModelBrowsePage(
    store: ProviderStore, picker: ChatModelPickerState, page: ChatModelPickerState.Page,
    suspended: Boolean, hideForReturn: Boolean, composerPadding: Dp,
    onEditModel: (RemoteModel, PickerBrowseSnapshot) -> Unit, modifier: Modifier = Modifier,
) {
    val catalog by store.catalog.collectAsState()
    val providers = remember(catalog.providers) { catalog.providers.filter { it.enabled && it.mode == Mode.Chat } }
    val entries = remember(catalog.providers) { catalog.entries(Mode.Chat) }
    val active = remember(catalog) { catalog.selectedEntry(Mode.Chat, entries) }
    val open = picker.modelListNavigation
    val interactive = open && !suspended && !hideForReturn
    if (interactive) ChatModelPickerBack(onBack = picker::systemBack, imeFirst = true)

    // The logical selection ends immediately. Keep its last visible results during the slide out.
    var lastQuery by remember(picker) { mutableStateOf(picker.searchQuery.text) }
    var lastProvider by remember(picker) { mutableStateOf(picker.browsedProviderId) }
    val query = if (open) picker.searchQuery.text else lastQuery
    val providerId = if (open) picker.browsedProviderId else lastProvider
    if (open) SideEffect { lastQuery = query; lastProvider = providerId }
    val browsing = providers.find { it.id == providerId } ?: active?.second ?: providers.firstOrNull()
    val restore = picker.pendingBrowseRestore
    LaunchedEffect(browsing?.id, interactive, restore) {
        if (browsing == null && restore != null) picker.consumeBrowseRestore(restore)
        else if (browsing != null && interactive && restore == null && picker.browsedProviderId != browsing.id) {
            picker.models(browsing.id)
        }
    }
    val colors = MaterialTheme.colorScheme
    val dark = colors.onSurface.luminance() > colors.surface.luminance()
    val headingColor = if (dark) Color(0xFFAFAFAF) else colors.onSurfaceVariant
    val pageInsets = composerPadding + 8.dp
    Box(modifier.fillMaxSize()) {
        key(browsing?.id) {
            // Keep the current supplier's list positions while the supplier chooser covers it.
            val browsingScroll = rememberLazyListState()
            val filteredScroll = rememberLazyListState()
            if (page == ChatModelPickerState.Page.Providers) {
                val index = providers.indexOfFirst { it.id == browsing?.id }.coerceAtLeast(0)
                val nameCounts = remember(providers) { providers.groupingBy { it.name }.eachCount() }
                LazyColumn(Modifier.fillMaxSize().testTag("chat-provider-list"),
                    contentPadding = PaddingValues(top = 8.dp, bottom = pageInsets),
                    state = rememberLazyListState(initialFirstVisibleItemIndex = index)) {
                    item { BrowseHeading(stringResource(R.string.chat_picker_provider), headingColor) }
                    items(providers, key = { it.id }) { candidate ->
                        val name = if (nameCounts.getValue(candidate.name) > 1)
                            "${candidate.name} · ${candidate.id}" else candidate.name
                        BrowseTile(name, candidate, null, candidate.id == browsing?.id, Modifier.testTag("chat-model-provider:${candidate.id}")) {
                            if (interactive && picker.page == ChatModelPickerState.Page.Providers) picker.models(candidate.id)
                        }
                    }
                }
            } else if (browsing == null) {
                LazyColumn(Modifier.fillMaxSize().testTag("chat-model-list"),
                    contentPadding = PaddingValues(top = 8.dp, bottom = pageInsets)) {
                    item { BrowseHeading(stringResource(R.string.chat_picker_provider), headingColor) }
                    item { BrowseHeading(stringResource(R.string.chat_picker_models), headingColor) }
                    item { Text(stringResource(R.string.chat_picker_no_models), Modifier.padding(horizontal = 14.dp)) }
                }
            } else {
                val models = remember(entries, browsing.id) {
                    entries.mapNotNull { (model, provider) -> model.takeIf { provider.id == browsing.id } }
                }
                ProviderModelsList(browsing, models, active?.route, picker, query.trim(), pageInsets,
                    browsingScroll, filteredScroll, dark, interactive, hideForReturn, onEditModel) { next ->
                    if (interactive && picker.modelListNavigation) {
                        val latest = store.catalog.value
                        val route = (next to browsing).route
                        val available = latest.entries(Mode.Chat)
                        val selected = available.find { it.route == route }
                        if (selected != null) {
                            if (latest.selectedEntry(Mode.Chat, available)?.route != route)
                                store.configure(Mode.Chat, route, latest.parameters(selected.first, selected.second))
                            picker.open()
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ProviderModelsList(
    provider: Provider, models: List<ProviderModel>, active: RemoteModel?, picker: ChatModelPickerState,
    query: String, pageInsets: Dp, browsingScroll: LazyListState, filteredScroll: LazyListState,
    dark: Boolean, interactive: Boolean, hideForReturn: Boolean,
    onEditModel: (RemoteModel, PickerBrowseSnapshot) -> Unit, onPick: (ProviderModel) -> Unit,
) {
    val matches = remember(models, query) { matchingMenuModels(models, query) }
    val nameCounts = remember(models) { models.groupingBy { it.name }.eachCount() }
    val scroll = if (query.isEmpty()) browsingScroll else filteredScroll
    val restore = picker.pendingBrowseRestore
    var savedRoom by remember(query) { mutableStateOf<Dp?>(null) }
    val request = picker.pendingSearchScroll
    LaunchedEffect(query, request, interactive, restore) {
        if (!interactive || restore != null || request == null || request != picker.pendingSearchScroll ||
            query != picker.searchQuery.text.trim()) return@LaunchedEffect
        val view = scroll.layoutInfo
        val unobscuredEnd = view.viewportEndOffset - view.afterContentPadding
        val groupFits = request == ChatModelPickerState.SearchScroll.Filter && scroll.firstVisibleItemIndex == 0 &&
            view.visibleItemsInfo.any { it.index == FirstModelRow && it.offset + it.size <= unobscuredEnd }
        scroll.requestScrollToItem(if (query.isEmpty() || groupFits) 0 else FirstModelRow)
        picker.consumeSearchScroll()
    }
    LaunchedEffect(restore, hideForReturn, interactive) {
        if (restore == null || (!interactive && !hideForReturn)) return@LaunchedEffect
        if (provider.id == restore.providerId) {
            savedRoom = restore.menuHeightDp.takeIf { it > 0f }?.dp
            val browsingIndex = restore.browsing.resolveIndex(menuRowKeys(provider.id, models))
            val filtered = matchingMenuModels(models, restore.query.text)
            val filteredIndex = restore.filtered.resolveIndex(menuRowKeys(provider.id, filtered))
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
    val colors = MaterialTheme.colorScheme
    val headingColor = if (dark) Color(0xFFAFAFAF) else colors.onSurfaceVariant
    BoxWithConstraints(Modifier.fillMaxSize().testTag("chat-model-list")) {
        val room = (maxHeight - pageInsets).coerceAtLeast(0.dp)
        // When the IME closes over a visit, keep the old browsing space until restoration.
        // A temporarily taller page must not clamp a saved near-end scroll anchor.
        val saved = restore?.takeIf { it.providerId == provider.id && it.menuHeightDp > 0f }?.menuHeightDp?.dp ?: savedRoom
        val heldRoom = if (!interactive && saved != null) minOf(room, saved) else room
        LazyColumn(Modifier.fillMaxSize().testTag("chat-model-results"), state = scroll,
            contentPadding = PaddingValues(top = 8.dp, bottom = maxHeight - heldRoom)) {
            item(key = ProviderRow) {
                Column {
                    BrowseHeading(stringResource(R.string.chat_picker_provider), headingColor)
                    BrowseTile(provider.name, provider, null, null, Modifier.testTag("chat-model-provider-trigger")) {
                        if (interactive) picker.providers()
                    }
                }
            }
            item(key = ModelsHeading) { BrowseHeading(stringResource(R.string.chat_picker_models), headingColor) }
            if (matches.isEmpty()) item(key = EmptyRow) {
                val empty = if (models.isEmpty()) R.string.chat_picker_provider_empty else R.string.chat_picker_search_empty
                Text(stringResource(empty), Modifier.testTag("chat-model-search-empty")
                    .semantics { liveRegion = LiveRegionMode.Polite }.padding(horizontal = 14.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            }
            items(matches, key = { modelRowKey(provider.id, it.id) }) { model ->
                val route = (model to provider).route
                val name = if (nameCounts[model.name] == 1) model.name else "${model.name} · ${model.id}"
                BrowseTile(name, provider, model, active == route, Modifier.testTag("chat-model-option:${provider.id}/${model.id}"),
                    onEdit = {
                        val page = picker.page
                        if (interactive && (page == ChatModelPickerState.Page.Models || page == ChatModelPickerState.Page.Search)) {
                            savedRoom = room
                            onEditModel(route, PickerBrowseSnapshot(page, provider.id, picker.searchQuery,
                                browsingScroll.anchor(), filteredScroll.anchor(), room.value))
                        }
                    }) { onPick(model) }
            }
        }
    }
}
