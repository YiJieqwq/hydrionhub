package io.hydrion.hub.provider

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.hydrion.hub.R
import io.hydrion.hub.model.RemoteModel

// Provider heading, provider switch, model heading precede the first result.
private const val FirstModelRow = 3

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
    picker: ChatModelPickerState, onPick: (ProviderModel) -> Unit,
) = key(provider.id) {
    val searching = picker.page == ChatModelPickerState.Page.Search
    val query = if (searching) picker.searchQuery.text.trim() else ""
    val matches = remember(models, query) { matchingMenuModels(models, query) }
    val nameCounts = remember(models) { models.groupingBy { it.name }.eachCount() }
    val browsingScroll = rememberLazyListState()
    val filteredScroll = rememberLazyListState()
    val scroll = if (query.isEmpty()) browsingScroll else filteredScroll
    LaunchedEffect(searching, query) {
        if (!searching || query.isEmpty()) return@LaunchedEffect
        val view = filteredScroll.layoutInfo
        val groupFits = filteredScroll.firstVisibleItemIndex == 0 && view.visibleItemsInfo.any {
            it.index == FirstModelRow && it.offset + it.size <= view.viewportEndOffset
        }
        filteredScroll.requestScrollToItem(if (groupFits) 0 else FirstModelRow)
    }
    LaunchedEffect(searching, picker.searchSubmissions) {
        if (searching && picker.searchSubmissions > 0) scroll.requestScrollToItem(if (query.isEmpty()) 0 else FirstModelRow)
    }
    val actionLabel = stringResource(if (searching) R.string.chat_picker_search_close else R.string.chat_picker_search)
    val colors = MaterialTheme.colorScheme
    val keepEditorFocus = Modifier.focusProperties { canFocus = false }

    Column(Modifier.fillMaxWidth().testTag("chat-model-list")) {
        LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false).testTag("chat-model-results"),
            state = scroll, contentPadding = PaddingValues(top = 6.dp)) {
            item { PickerHeading(stringResource(R.string.chat_picker_provider)) }
            item {
                Row(keepEditorFocus.fillMaxWidth().testTag("chat-model-provider-trigger")
                    .clickable(role = Role.Button, onClick = picker::providers).heightIn(min = 48.dp)
                    .padding(horizontal = 14.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(provider.name, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null, Modifier.size(18.dp))
                }
            }
            item { PickerHeading(stringResource(R.string.chat_picker_models)) }
            if (matches.isEmpty()) item {
                val empty = if (models.isEmpty()) R.string.chat_picker_provider_empty else R.string.chat_picker_search_empty
                Text(stringResource(empty), Modifier.testTag("chat-model-search-empty")
                    .semantics { liveRegion = LiveRegionMode.Polite }.padding(horizontal = 14.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            }
            items(matches, key = { it.id }) { model ->
                val route = (model to provider).route
                val label = if (nameCounts[model.name] == 1) model.name else "${model.name} · ${model.id}"
                PickerOption(label, active == route, Modifier.testTag("chat-model-option:${provider.id}/${model.id}"),
                    maxLines = 1) { onPick(model) }
            }
        }
        // Keep the same independent control and hit rectangle in both states.
        val enabled = searching || models.isNotEmpty()
        Box(Modifier.align(Alignment.End).padding(end = 4.dp, bottom = 2.dp).size(48.dp)
            .then(keepEditorFocus).clip(CircleShape).testTag(if (searching) "chat-model-search-close" else "chat-model-search")
            .semantics { contentDescription = actionLabel }
            .clickable(enabled = enabled, role = Role.Button) {
                if (searching) picker.back() else picker.search()
            }, contentAlignment = Alignment.Center) {
            Surface(modifier = Modifier.size(36.dp), shape = CircleShape, color = colors.surfaceContainerHigh,
                border = BorderStroke(0.75.dp, colors.outlineVariant), shadowElevation = 2.dp) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(if (searching) Icons.Outlined.Close else Icons.Outlined.Search, null, Modifier.size(19.dp),
                        tint = colors.onSurface.copy(alpha = if (enabled) 1f else .38f))
                }
            }
        }
    }
}
