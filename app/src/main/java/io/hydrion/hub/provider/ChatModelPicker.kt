package io.hydrion.hub.provider

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import io.hydrion.hub.R
import io.hydrion.hub.model.Mode
import io.hydrion.hub.model.RemoteModel
import kotlin.math.roundToInt

internal class ChatModelPickerState {
    enum class Page { Controls, Models, Providers, Options }

    var page by mutableStateOf<Page?>(null)
        private set
    var optionKey by mutableStateOf("")
        private set
    var composerBounds by mutableStateOf<IntRect?>(null)
    var browsedProviderId by mutableStateOf<String?>(null)
        private set

    fun open() { browsedProviderId = null; page = Page.Controls }
    fun models(providerId: String) { browsedProviderId = providerId; page = Page.Models }
    fun providers() { page = Page.Providers }
    fun options(cap: Capability) { optionKey = cap.key; page = Page.Options }
    fun dismiss() { page = null }
    fun back() {
        page = when (page) {
            Page.Providers -> Page.Models
            Page.Models, Page.Options -> Page.Controls
            else -> null
        }
    }
}

@Composable
internal fun ChatModelPicker(store: ProviderStore, picker: ChatModelPickerState, modifier: Modifier = Modifier) {
    val page = picker.page ?: return
    val catalog by store.catalog.collectAsState()
    val models = remember(catalog) { catalog.entries(Mode.Chat) }
    val entry = catalog.selectedEntry(Mode.Chat)
    val select = stringResource(R.string.chat_controls_select_model)
    val close = stringResource(R.string.chat_picker_close)
    ChatModelPickerBack(picker::back)

    // The IME stays in the editor's window. The transparent sibling catches only outside taps.
    Box(modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Vertical))) {
        Box(Modifier.matchParentSize().focusProperties { canFocus = false }
            .clickable(interactionSource = null, indication = null, role = Role.Button, onClick = picker::dismiss)
            .semantics { contentDescription = close })
        var origin by remember { mutableStateOf<IntOffset?>(null) }
        Layout(
            modifier = Modifier.fillMaxSize().onGloballyPositioned { origin = it.positionInWindow().round() },
            content = {
                Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surface,
                    border = BorderStroke(0.66.dp, MaterialTheme.colorScheme.outlineVariant), shadowElevation = 6.dp,
                    modifier = Modifier.semantics { paneTitle = select }) {
                    if (entry == null) {
                        Text(stringResource(R.string.chat_picker_no_models), Modifier.padding(16.dp),
                            style = MaterialTheme.typography.bodyMedium)
                    } else {
                        val (model, provider) = entry
                        val values = catalog.parameters(model, provider)
                        val cap = model.configureCapabilities().find { it.key == picker.optionKey }
                        when {
                            page == ChatModelPickerState.Page.Models || page == ChatModelPickerState.Page.Providers -> {
                                val providers = remember(catalog) { catalog.providers.filter { it.enabled && it.mode == Mode.Chat } }
                                val browsing = providers.find { it.id == picker.browsedProviderId } ?: provider
                                if (page == ChatModelPickerState.Page.Providers) {
                                    PickerList(stringResource(R.string.chat_picker_provider), Modifier.testTag("chat-provider-list"),
                                        selectedIndex = providers.indexOf(browsing)) {
                                        items(providers, key = { it.id }) { candidate ->
                                            val label = if (providers.count { it.name == candidate.name } > 1)
                                                "${candidate.name} · ${candidate.id}" else candidate.name
                                            PickerOption(label, candidate.id == browsing.id,
                                                Modifier.testTag("chat-model-provider:${candidate.id}")) {
                                                picker.models(candidate.id)
                                            }
                                        }
                                    }
                                } else {
                                    ProviderModelsList(browsing, models.filter { it.second.id == browsing.id }.map { it.first },
                                        entry.route, onProviders = picker::providers, onPick = { next ->
                                            val route = (next to browsing).route
                                            if (route != entry.route) store.configure(Mode.Chat, route, catalog.parameters(next, browsing))
                                            picker.open()
                                        })
                                }
                            }
                            page == ChatModelPickerState.Page.Options && cap != null -> {
                                PickerList(chatCapabilityLabel(cap), selectedIndex = cap.choices.indexOf(values[cap.key])) {
                                    items(cap.choices, key = { it }) { value ->
                                        PickerOption(chatOptionLabel(cap, value), values[cap.key] == value) {
                                            store.configure(Mode.Chat, entry.route, model.choose(values, cap.key, value))
                                            picker.open()
                                        }
                                    }
                                }
                            }
                            else -> Column(Modifier.verticalScroll(rememberScrollState()).testTag("chat-model-card")) {
                                val speeds = remember(model, provider, values) { model.chatSpeedControls(provider, values) }
                                ChatModelControls(model, values, speeds = speeds, onModels = { picker.models(provider.id) },
                                    onChoose = { capability, choice ->
                                        store.configure(Mode.Chat, entry.route, model.choose(values, capability.key, choice))
                                    },
                                    onOptions = picker::options,
                                    onReset = {
                                        catalog.defaultEntry(Mode.Chat)?.let { default ->
                                            store.configure(Mode.Chat, default.route, catalog.parameters(default.first, emptyMap()))
                                        }
                                    })
                            }
                        }
                    }
                }
            },
        ) { children, limits ->
            val windowOrigin = origin
            val composer = picker.composerBounds
            if (windowOrigin == null || composer == null) {
                // The safe viewport can start below a status bar or cutout. Wait for its coordinates
                // before placing the first visible frame.
                layout(limits.maxWidth, limits.maxHeight) {}
            } else {
                val viewport = IntSize(limits.maxWidth, limits.maxHeight)
                val space = pickerSpace(composer.translate(-windowOrigin), viewport, layoutDirection)
                val card = children.single().measure(Constraints(minWidth = space.width, maxWidth = space.width,
                    maxHeight = space.maxHeight))
                val y = if (space.above) space.edgeY - card.height else space.edgeY
                layout(viewport.width, viewport.height) { card.place(space.left, y) }
            }
        }
    }
}

internal data class PickerSpace(val left: Int, val edgeY: Int, val width: Int, val maxHeight: Int, val above: Boolean)

internal fun Density.pickerSpace(
    composer: IntRect, viewport: IntSize, direction: LayoutDirection,
): PickerSpace {
    val gap = 11.5.dp.roundToPx()
    val sideMargin = minOf(12.dp.roundToPx(), viewport.width / 2)
    val verticalMargin = minOf(12.dp.roundToPx(), viewport.height / 2)
    val preferredWidth = (viewport.width * 0.6f).roundToInt().coerceIn(240.dp.roundToPx(), 248.dp.roundToPx())
    val width = minOf(preferredWidth, viewport.width - 2 * sideMargin)
    val endAligned = if (direction == LayoutDirection.Ltr) composer.right - width else composer.left
    val left = endAligned.coerceIn(sideMargin, viewport.width - sideMargin - width)
    val upperEdge = (composer.top - gap).coerceIn(verticalMargin, viewport.height - verticalMargin)
    val lowerEdge = (composer.bottom + gap).coerceIn(verticalMargin, viewport.height - verticalMargin)
    val above = upperEdge - verticalMargin
    val below = viewport.height - verticalMargin - lowerEdge
    // In split-screen a tall draft can fill almost the entire area above the IME. Give the
    // scrollable content a heading and a tappable row, even if that uses the top of the editor.
    val oneRow = minOf(88.dp.roundToPx(), viewport.height)
    return if (above >= 96.dp.roundToPx() || above >= below) {
        PickerSpace(left, maxOf(upperEdge, oneRow), width, maxOf(above, oneRow), true)
    } else {
        PickerSpace(left, minOf(lowerEdge, viewport.height - oneRow), width, maxOf(below, oneRow), false)
    }
}

@Composable
private fun ProviderModelsList(
    provider: Provider, models: List<ProviderModel>, active: RemoteModel,
    onProviders: () -> Unit, onPick: (ProviderModel) -> Unit,
) {
    val providerTitle = stringResource(R.string.chat_picker_provider)
    val sectionTitle = stringResource(R.string.chat_picker_models)
    val nameCounts = remember(models) { models.groupingBy { it.name }.eachCount() }
    key(provider.id) {
        LazyColumn(Modifier.fillMaxWidth().testTag("chat-model-list"),
            contentPadding = PaddingValues(vertical = 6.dp)) {
            item { PickerHeading(providerTitle) }
            item {
                Row(Modifier.fillMaxWidth().focusProperties { canFocus = false }.testTag("chat-model-provider-trigger")
                    .clickable(role = Role.Button, onClick = onProviders).heightIn(min = 48.dp)
                    .padding(horizontal = 14.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(provider.name, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null, Modifier.size(18.dp))
                }
            }
            item { PickerHeading(sectionTitle) }
            if (models.isEmpty()) item {
                Text(stringResource(R.string.chat_picker_provider_empty), Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            items(models, key = { it.id }) { model ->
                val route = (model to provider).route
                val label = if (nameCounts[model.name] == 1) model.name else "${model.name} · ${model.id}"
                PickerOption(label, active == route, Modifier.testTag("chat-model-option:${provider.id}/${model.id}")) {
                    onPick(model)
                }
            }
        }
    }
}

@Composable
private fun PickerHeading(title: String) {
    Text(title, Modifier.padding(horizontal = 14.dp, vertical = 5.dp).semantics { heading() },
        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun PickerList(
    title: String, modifier: Modifier = Modifier, selectedIndex: Int,
    items: LazyListScope.() -> Unit,
) {
    Column(modifier.padding(vertical = 6.dp)) {
        PickerHeading(title)
        val scroll = rememberLazyListState(initialFirstVisibleItemIndex = (selectedIndex - 1).coerceAtLeast(0))
        LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false).selectableGroup(), state = scroll, content = items)
    }
}

@Composable
private fun PickerOption(label: String, selected: Boolean, modifier: Modifier = Modifier, onPick: () -> Unit) {
    Row(modifier.fillMaxWidth().focusProperties { canFocus = false }
        .selectable(selected, role = Role.RadioButton, onClick = onPick)
        .heightIn(min = 48.dp).padding(horizontal = 14.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium,
            maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (selected) Icon(Icons.Outlined.Check, null, Modifier.size(18.dp))
    }
}
