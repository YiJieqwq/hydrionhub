package io.hydrion.hub.provider

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import io.hydrion.hub.ui.composer.PanelBottomRail
import io.hydrion.hub.ui.composer.PanelModelSearch
import io.hydrion.hub.ui.composer.PanelViewport
import io.hydrion.hub.ui.composer.PanelStep
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
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

private const val FOLLOW_DEFAULT_KEY = "follow-chat-default"
private const val FAVORITES_HEADING_KEY = "chat-favorites-heading"

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

internal data class ModelMenuRow(val provider: Provider, val model: ProviderModel?, val label: String,
    val providerLabel: String = label, val favoriteShortcut: Boolean = false) {
    val route = model?.let { RemoteModel(provider.id, it.id) }
    val key = when {
        model == null -> "heading:${provider.id}"
        favoriteShortcut -> "favorite:${provider.id.length}:${provider.id}/${model.id}"
        else -> "model:${provider.id.length}:${provider.id}/${model.id}"
    }
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
                    add(ModelMenuRow(provider, model, label, providerLabel = name))
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
    selected: RemoteModel?, onPick: (RemoteModel) -> Unit, onEdit: ((RemoteModel) -> Unit)? = null,
    favorites: Set<RemoteModel> = emptySet(), onFavoriteChange: ((RemoteModel, Boolean) -> Unit)? = null) {
    items(rows, key = { it.key }, contentType = { if (it.model == null) "header" else "model" }) { row ->
        val model = row.model
        if (model == null) PanelStep(Modifier.fillMaxWidth()) { BrowseHeading(row.label) }
        else {
            val route = requireNotNull(row.route)
            val prefix = if (row.favoriteShortcut) "chat-model-favorite-option" else "chat-model-option"
            val tag = "$prefix:${route.providerId}/${route.modelId}"
            val qualifiedLabel = stringResource(R.string.browse_model_with_provider, row.label, row.providerLabel)
            val label = if (row.favoriteShortcut) qualifiedLabel else row.label
            PanelStep(Modifier.fillMaxWidth()) { BrowseTile(label, row.provider, model, route == selected, Modifier.testTag(tag),
                onEdit = onEdit?.let { edit -> { edit(route) } },
                favoriteButton = if (onFavoriteChange == null) null else ({
                    ModelFavoriteButton(route in favorites, qualifiedLabel, Modifier.testTag("$tag:favorite")) {
                        onFavoriteChange(route, it)
                    }
                }), onPick = { onPick(route) }) }
        }
    }
}

@Composable
internal fun ChatModelBrowsePage(
    store: ProviderStore, picker: ChatModelPickerState, chatOverride: RemoteModel?, suspended: Boolean, hideForReturn: Boolean, composerPadding: Dp = 0.dp,
    settled: Boolean = true,
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
    val shortcuts = remember(all, catalog.favorites) {
        if (catalog.favorites.isEmpty()) emptyList() else all.mapNotNull { row ->
            if (row.route in catalog.favorites) row.copy(favoriteShortcut = true) else null
        }
    }
    val browseRows = remember(all, shortcuts) { if (shortcuts.isEmpty()) all else shortcuts + all }
    val rows = if (query.isEmpty()) browseRows else remember(entries, labels, query) { modelMenuRows(entries, query, labels) }
    val showFollow = catalog.perChatModel && query.isEmpty()
    val browsingScroll = rememberLazyListState()
    val filteredScroll = rememberLazyListState()
    val scroll = if (query.isEmpty()) browsingScroll else filteredScroll
    val restore = picker.pendingBrowseRestore
    val request = picker.pendingSearchScroll
    val providers = remember(all) { all.filter { it.model == null } }
    val headingIndex = remember(rows) {
        rows.withIndex().filter { it.value.model == null }.associate { it.value.provider.id to it.index }
    }
    val prefixCount = (if (showFollow) 1 else 0) + (if (query.isEmpty() && shortcuts.isNotEmpty()) 1 else 0)
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
        val allKeys = buildList {
            if (catalog.perChatModel) add(FOLLOW_DEFAULT_KEY)
            if (shortcuts.isNotEmpty()) add(FAVORITES_HEADING_KEY)
            browseRows.mapTo(this) { it.key }
        }
        val fullIndex = restore.browsing.resolveIndex(allKeys)
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
    Column(modifier.fillMaxWidth().testTag("chat-model-list").padding(bottom = if (providers.isEmpty()) 6.dp else 0.dp)) {
        PanelModelSearch(picker, enabled = interactive && settled, onFavorites = if (shortcuts.isEmpty()) null else ({
            picker.clearSearchQuery()
            browsingScroll.requestScrollToItem(if (catalog.perChatModel) 1 else 0)
        }))
        Spacer(Modifier.height(8.dp))
        BoxWithConstraints(Modifier.weight(1f, fill = false).fillMaxWidth().padding(horizontal = 12.dp)) {
            val room = maxHeight
            PanelViewport(Modifier.fillMaxWidth()) { LazyColumn(Modifier.fillMaxWidth().testTag("chat-model-results"), state = scroll,
                contentPadding = PaddingValues(top = 6.dp, bottom = composerPadding + 6.dp)) {
                if (showFollow) item(key = FOLLOW_DEFAULT_KEY) {
                    PanelStep(Modifier.fillMaxWidth()) { TextButton(
                        onClick = { store.followChatDefault(); picker.open() },
                        enabled = interactive && chatOverride != null,
                        modifier = Modifier.fillMaxWidth().focusProperties { canFocus = false }.testTag("chat-model-follow-default"),
                    ) {
                        Text(stringResource(if (chatOverride == null) R.string.chat_model_following_default else R.string.chat_model_follow_default))
                    } }
                }
                if (query.isEmpty() && shortcuts.isNotEmpty()) item(key = FAVORITES_HEADING_KEY) {
                    PanelStep(Modifier.fillMaxWidth()) { BrowseHeading(stringResource(R.string.browse_favorites)) }
                }
                if (rows.isEmpty()) item(key = "empty") {
                    Text(stringResource(if (query.isEmpty()) R.string.chat_picker_no_models else R.string.chat_picker_search_empty),
                        Modifier.testTag("chat-model-search-empty").semantics { liveRegion = LiveRegionMode.Polite }
                            .padding(horizontal = 14.dp, vertical = 8.dp), style = MaterialTheme.typography.bodySmall, color = browseColors().heading)
                }
                modelMenuItems(rows, active, onPick = pick@{ route ->
                    if (!interactive || !picker.modelListNavigation) return@pick
                    val latest = store.catalog.value
                    val selected = latest.entries(Mode.Chat).find { it.route == route } ?: return@pick
                    store.configure(Mode.Chat, route, latest.parameters(selected.first, selected.second))
                    picker.open()
                }, onEdit = { route ->
                    val page = picker.page
                    if (interactive && (page == ChatModelPickerState.Page.Models || page == ChatModelPickerState.Page.Search)) {
                        onEditModel(route, PickerBrowseSnapshot(page, picker.searchQuery,
                            browsingScroll.anchor(), filteredScroll.anchor(), room.value))
                    }
                }, favorites = catalog.favorites, onFavoriteChange = { route, favorite ->
                    if (interactive && picker.modelListNavigation) store.setChatModelFavorite(route, favorite)
                })
            } }
        }
        if (providers.isNotEmpty()) {
            val visibleProvider by remember(rows, scroll, prefixCount) {
                derivedStateOf { rows.getOrNull(scroll.firstVisibleItemIndex - prefixCount)?.takeUnless { it.favoriteShortcut }?.provider?.id }
            }
            PanelBottomRail {
                LazyRow(Modifier.fillMaxWidth().padding(horizontal = 12.dp).testTag("chat-model-providers"), userScrollEnabled = interactive && settled, horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    items(providers, key = { it.provider.id }) { heading ->
                        val index = headingIndex[heading.provider.id]
                        FilterChip(selected = heading.provider.id == visibleProvider, enabled = index != null,
                            onClick = {
                                if (!interactive || !settled || index == null) return@FilterChip
                                picker.consumeSearchScroll()
                                scroll.requestScrollToItem(prefixCount + index)
                            }, label = { Text(heading.label, maxLines = 1) },
                            modifier = Modifier.focusProperties { canFocus = interactive && settled }.testTag("chat-model-provider:${heading.provider.id}"))
                    }
                }
            }
        }
    }
}
