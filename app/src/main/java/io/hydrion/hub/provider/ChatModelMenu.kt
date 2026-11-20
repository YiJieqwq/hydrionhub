package io.hydrion.hub.provider

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
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
    var query by remember(searching) { mutableStateOf("") }
    val matches = remember(models, query) { matchingMenuModels(models, query) }
    val nameCounts = remember(models) { models.groupingBy { it.name }.eachCount() }
    val browsingScroll = rememberLazyListState()
    val scroll = if (searching) rememberLazyListState() else browsingScroll
    val searchLabel = stringResource(R.string.chat_picker_search)
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
        // The independent action and the last row never share a hit rectangle.
        if (!searching) {
            Box(Modifier.align(Alignment.End).padding(end = 4.dp, bottom = 2.dp).size(48.dp)
                .then(keepEditorFocus).clip(CircleShape).testTag("chat-model-search")
                .semantics { contentDescription = searchLabel }
                .clickable(enabled = models.isNotEmpty(), role = Role.Button, onClick = picker::search),
                contentAlignment = Alignment.Center) {
                Surface(modifier = Modifier.size(36.dp), shape = CircleShape, color = colors.surfaceContainerHigh,
                    border = BorderStroke(0.75.dp, colors.outlineVariant), shadowElevation = 2.dp) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(Icons.Outlined.Search, null, Modifier.size(19.dp),
                            tint = colors.onSurface.copy(alpha = if (models.isEmpty()) .38f else 1f))
                    }
                }
            }
        } else {
            MenuSearchField(picker, query, searchLabel,
                onQuery = { next ->
                    if (query.trim() != next.trim()) {
                        val view = scroll.layoutInfo
                        val groupFits = scroll.firstVisibleItemIndex == 0 && view.visibleItemsInfo.any {
                            it.index == FirstModelRow && it.offset + it.size <= view.viewportEndOffset
                        }
                        // In a short IME viewport, put a match (or the empty message) in view as the user types.
                        scroll.requestScrollToItem(if (next.isBlank() || groupFits) 0 else FirstModelRow)
                    }
                    query = next
                },
                onSubmit = { scroll.requestScrollToItem(if (query.isBlank()) 0 else FirstModelRow) })
        }
    }
}

@Composable
private fun MenuSearchField(
    picker: ChatModelPickerState, query: String, label: String,
    onQuery: (String) -> Unit, onSubmit: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically) {
        BasicTextField(value = query, onValueChange = onQuery, singleLine = true,
            modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("chat-model-search-field")
                .semantics { contentDescription = label }.chatPickerSearchInput(picker),
            textStyle = MaterialTheme.typography.bodyMedium.copy(color = colors.onSurface),
            cursorBrush = SolidColor(colors.onSurface), keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { onSubmit() }),
            decorationBox = { input ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(Icons.Outlined.Search, null, Modifier.size(18.dp), tint = colors.onSurfaceVariant)
                    Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                        if (query.isEmpty()) Text(label, color = colors.onSurfaceVariant,
                            style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        input()
                    }
                }
            })
        Box(Modifier.size(48.dp).focusProperties { canFocus = false }.clip(CircleShape)
            .testTag("chat-model-search-close").clickable(role = Role.Button, onClick = picker::back),
            contentAlignment = Alignment.Center) {
            Icon(Icons.Outlined.Close, stringResource(R.string.chat_picker_search_close), Modifier.size(18.dp))
        }
    }
}
