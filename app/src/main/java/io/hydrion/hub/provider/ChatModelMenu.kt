package io.hydrion.hub.provider

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
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

private fun LazyListState.anchor(): PickerScrollAnchor {
    val index = firstVisibleItemIndex
    val key = layoutInfo.visibleItemsInfo.firstOrNull { it.index == index }?.key as? String
    return PickerScrollAnchor(key, index, firstVisibleItemScrollOffset)
}

internal fun matchingMenuModels(models: List<ProviderModel>, query: String): List<ProviderModel> {
    val term = query.trim()
    if (term.isEmpty()) return models
    return models.filter {
        it.name.contains(term, ignoreCase = true) ||
            it.profile.externalId.contains(term, ignoreCase = true)
    }
}

internal data class ModelMenuRow(val provider: Provider, val model: ProviderModel?, val label: String) {
    val key = if (model == null) "heading:${provider.id}" else "model:${provider.id.length}:${provider.id}/${model.id}"
}

internal fun modelMenuRows(entries: List<Pair<ProviderModel, Provider>>, query: String,
    labels: Map<String, String> = emptyMap()): List<ModelMenuRow> {
    val groups = entries.groupBy { it.second.id }
    val providerNames = groups.values.groupingBy { group ->
        val provider = group.first().second
        labels[provider.id] ?: provider.name
    }.eachCount()
    val term = query.trim()
    return buildList {
        groups.values.forEach { group ->
            val provider = group.first().second
            val models = group.map { it.first }
            val title = labels[provider.id] ?: provider.name
            val matches = matchingMenuModels(models, term)
            if (matches.isNotEmpty()) {
                val name = if (providerNames[title] == 1) title else "$title · ${provider.id}"
                add(ModelMenuRow(provider, null, name))
                val names = models.groupingBy { it.name }.eachCount()
                matches.forEach { model ->
                    val label = if (names[model.name] == 1) model.name else "${model.name} · ${model.id}"
                    add(ModelMenuRow(provider, model, label))
                }
            }
        }
    }
}

@Composable
internal fun modelGroupLabels(entries: List<Pair<ProviderModel, Provider>>): Map<String, String> {
    val providers = remember(entries) { entries.map { it.second }.distinctBy { it.id } }
    return providers.associate { it.id to providerLabel(it) }
}

internal fun LazyListScope.modelMenuItems(rows: List<ModelMenuRow>,
    selected: RemoteModel?, onPick: (RemoteModel) -> Unit, onEdit: ((RemoteModel) -> Unit)? = null) {
    items(rows, key = { it.key }, contentType = { if (it.model == null) "header" else "model" }) { row ->
        val model = row.model
        if (model == null) BrowseHeading(row.label)
        else {
            val route = RemoteModel(row.provider.id, model.id)
            BrowseTile(row.label, row.provider, model, route == selected,
                Modifier.testTag("chat-model-option:${route.providerId}/${route.modelId}"),
                onEdit = onEdit?.let { edit -> { edit(route) } }, onPick = { onPick(route) })
        }
    }
}

@Composable
internal fun ChatModelBrowsePage(
    store: ProviderStore, picker: ChatModelPickerState, suspended: Boolean, hideForReturn: Boolean, composerPadding: Dp,
    onEditModel: (RemoteModel, PickerBrowseSnapshot) -> Unit, modifier: Modifier = Modifier,
) {
    val catalog by store.catalog.collectAsState()
    val entries = remember(catalog.providers) { catalog.entries(Mode.Chat) }
    val active = catalog.selectedEntry(Mode.Chat, entries)?.route
    val open = picker.modelListNavigation
    val interactive = open && !suspended && !hideForReturn
    if (interactive) ChatModelPickerBack(onBack = picker::systemBack, imeFirst = true)

    var lastQuery by remember(picker) { mutableStateOf(picker.searchQuery.text) }
    val query = (if (open) picker.searchQuery.text else lastQuery).trim()
    if (open) SideEffect { lastQuery = query }
    val labels = modelGroupLabels(entries)
    val all = remember(entries, labels) { modelMenuRows(entries, "", labels) }
    val rows = remember(all, query) { if (query.isEmpty()) all else modelMenuRows(entries, query, labels) }
    val browsingScroll = rememberLazyListState()
    val filteredScroll = rememberLazyListState()
    val scroll = if (query.isEmpty()) browsingScroll else filteredScroll
    val restore = picker.pendingBrowseRestore
    val request = picker.pendingSearchScroll
    var savedRoom by remember(query) { mutableStateOf<Dp?>(null) }
    LaunchedEffect(query, request, interactive, restore) {
        if (interactive && request != null && restore == null && query == picker.searchQuery.text.trim()) {
            val firstModel = rows.indexOfFirst { it.model != null }.coerceAtLeast(0)
            val layout = scroll.layoutInfo
            val fits = request == ChatModelPickerState.SearchScroll.Filter && scroll.firstVisibleItemIndex == 0 &&
                layout.visibleItemsInfo.any { it.index == firstModel && it.offset + it.size <= layout.viewportEndOffset - layout.afterContentPadding }
            scroll.requestScrollToItem(if (query.isEmpty() || fits) 0 else firstModel)
            picker.consumeSearchScroll()
        }
    }
    LaunchedEffect(restore, hideForReturn, interactive) {
        if (restore == null || (!interactive && !hideForReturn)) return@LaunchedEffect
        savedRoom = restore.menuHeightDp.takeIf { it > 0f }?.dp
        val fullIndex = restore.browsing.resolveIndex(all.map { it.key })
        val resultIndex = restore.filtered.resolveIndex(rows.map { it.key })
        if (scroll === browsingScroll) {
            filteredScroll.requestScrollToItem(resultIndex, restore.filtered.offset)
            browsingScroll.scrollToItem(fullIndex, restore.browsing.offset)
        } else {
            browsingScroll.requestScrollToItem(fullIndex, restore.browsing.offset)
            filteredScroll.scrollToItem(resultIndex, restore.filtered.offset)
        }
        picker.consumeBrowseRestore(restore)
    }
    BoxWithConstraints(modifier.fillMaxSize().testTag("chat-model-list")) {
        val room = (maxHeight - composerPadding - 8.dp).coerceAtLeast(0.dp)
        val saved = restore?.menuHeightDp?.takeIf { it > 0f }?.dp ?: savedRoom
        val heldRoom = if (!interactive && saved != null) minOf(room, saved) else room
        LazyColumn(Modifier.fillMaxSize().testTag("chat-model-results"), state = scroll,
            contentPadding = PaddingValues(top = 8.dp, bottom = maxHeight - heldRoom)) {
            if (rows.isEmpty()) item(key = "empty") {
                Text(stringResource(if (query.isEmpty()) R.string.chat_picker_no_models else R.string.chat_picker_search_empty),
                    Modifier.testTag("chat-model-search-empty").semantics { liveRegion = LiveRegionMode.Polite }
                        .padding(horizontal = 14.dp, vertical = 8.dp), style = MaterialTheme.typography.bodySmall, color = browseColors().heading)
            }
            modelMenuItems(rows, active, onPick = pick@{ route ->
                if (!interactive || !picker.modelListNavigation) return@pick
                val latest = store.catalog.value
                val selected = latest.entries(Mode.Chat).find { it.route == route } ?: return@pick
                if (latest.selectedEntry(Mode.Chat)?.route != route)
                    store.configure(Mode.Chat, route, latest.parameters(selected.first, selected.second))
                picker.open()
            }, onEdit = { route ->
                val page = picker.page
                if (interactive && (page == ChatModelPickerState.Page.Models || page == ChatModelPickerState.Page.Search)) {
                    savedRoom = room
                    onEditModel(route, PickerBrowseSnapshot(page, picker.searchQuery,
                        browsingScroll.anchor(), filteredScroll.anchor(), room.value))
                }
            })
        }
    }
}
