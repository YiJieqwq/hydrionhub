package io.hydrion.hub.provider

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import io.hydrion.hub.R
import io.hydrion.hub.model.Mode
import io.hydrion.hub.model.RemoteModel
import kotlin.math.roundToInt

internal class ChatModelPickerState {
    enum class Page { Controls, Models, Search, Providers, Options }
    enum class SearchScroll { Filter, Submit }

    var page by mutableStateOf<Page?>(null)
        private set
    val modelListNavigation: Boolean
        get() = page == Page.Models || page == Page.Search || page == Page.Providers
    var optionKey by mutableStateOf("")
        private set
    var composerBounds by mutableStateOf<IntRect?>(null)
    var editorBounds by mutableStateOf<IntRect?>(null)
    var editorTextBounds by mutableStateOf<IntRect?>(null)
    var triggerBounds by mutableStateOf<IntRect?>(null)
    var modelLabelInTrigger by mutableStateOf<IntRect?>(null)
    val modelLabelBounds: IntRect?
        get() = triggerBounds?.let { hit ->
            modelLabelInTrigger?.translate(hit.topLeft)?.intersect(hit)?.takeIf { it.width > 0 && it.height > 0 }
        }
    var searchQuery by mutableStateOf(TextFieldValue())
        private set
    var pendingSearchScroll by mutableStateOf<SearchScroll?>(null)
        private set
    var browsedProviderId by mutableStateOf<String?>(null)
        private set
    var pendingBrowseRestore by mutableStateOf<PickerBrowseSnapshot?>(null)
        private set

    fun restoreBrowsing(snapshot: PickerBrowseSnapshot) {
        browsedProviderId = snapshot.providerId
        page = snapshot.page
        searchQuery = snapshot.query.copy(composition = null)
        pendingSearchScroll = null
        pendingBrowseRestore = snapshot
    }

    fun consumeBrowseRestore(snapshot: PickerBrowseSnapshot, availableProvider: String = snapshot.providerId) {
        if (pendingBrowseRestore === snapshot) {
            browsedProviderId = availableProvider
            pendingBrowseRestore = null
        }
    }

    private fun show(next: Page?) {
        pendingBrowseRestore = null
        if (next == null || next == Page.Controls || next == Page.Options) {
            searchQuery = TextFieldValue()
        } else if (page == Page.Search && next != Page.Search && searchQuery.composition != null) {
            // Keep the query and selection while browsing; composition belongs to the old IME session.
            searchQuery = searchQuery.copy(composition = null)
        }
        if (next != Page.Search) pendingSearchScroll = null
        page = next
    }
    fun open() { browsedProviderId = null; show(Page.Controls) }
    fun models(providerId: String, startSearching: Boolean = false) {
        if (startSearching && page != Page.Controls) return
        val changedProvider = providerId != browsedProviderId
        browsedProviderId = providerId
        show(if (startSearching) Page.Search else Page.Models)
        if (changedProvider && searchQuery.text.isNotBlank()) pendingSearchScroll = SearchScroll.Filter
    }
    fun search() {
        if (page == Page.Models) show(Page.Search)
    }
    fun updateSearchQuery(value: TextFieldValue) {
        if (page != Page.Search) return
        val term = value.text.trim()
        if (term != searchQuery.text.trim()) pendingSearchScroll = if (term.isEmpty()) null else SearchScroll.Filter
        searchQuery = value
    }
    fun submitSearch() {
        if (page == Page.Search) pendingSearchScroll = SearchScroll.Submit
    }
    fun consumeSearchScroll() { pendingSearchScroll = null }
    fun providers() { show(Page.Providers) }
    fun options(cap: Capability) { optionKey = cap.key; show(Page.Options) }
    fun dismiss() { show(null) }
    fun systemBack() {
        if (modelListNavigation) dismiss() else back()
    }
    fun back() {
        show(when (page) {
            Page.Providers, Page.Search -> Page.Models
            Page.Models, Page.Options -> Page.Controls
            else -> null
        })
    }
}

@Composable
internal fun ChatModelPicker(
    store: ProviderStore, picker: ChatModelPickerState,
    suspended: Boolean, hideForReturn: Boolean, onEditModel: (RemoteModel, PickerBrowseSnapshot) -> Unit,
    modifier: Modifier = Modifier,
) {
    val page = picker.page
    val face = when (page) {
        null -> PickerBubbleFace.Label
        ChatModelPickerState.Page.Controls -> PickerBubbleFace.Effort
        else -> PickerBubbleFace.List
    }
    val modelList = page == ChatModelPickerState.Page.Models || page == ChatModelPickerState.Page.Search
    val paused = suspended || hideForReturn
    val catalog by store.catalog.collectAsState()
    val models = remember(catalog) { catalog.entries(Mode.Chat) }
    val entry = catalog.selectedEntry(Mode.Chat)
    val select = stringResource(R.string.chat_controls_select_model)
    val close = stringResource(R.string.chat_picker_close)
    if (page != null && !paused) ChatModelPickerBack(onBack = picker::systemBack, imeFirst = picker.modelListNavigation)
    val colors = MaterialTheme.colorScheme
    val dark = colors.onSurface.luminance() > colors.surface.luminance()

    // Controls and search keep their original editor hit area. Other pages pass through only the text row.
    val accessibility = if (paused) Modifier.clearAndSetSemantics {} else Modifier
    var origin by remember { mutableStateOf<IntOffset?>(null) }
    Box(modifier.then(accessibility).windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Vertical))) {
        // Measure inside the safe insets even while the picker is idle, before its first frame.
        Spacer(Modifier.matchParentSize().onGloballyPositioned { origin = it.positionInWindow().round() })
        if (page != null && !paused) {
            val editThrough = page == ChatModelPickerState.Page.Controls || page == ChatModelPickerState.Page.Search
            PickerDismissLayer(
                picker, close, Modifier.matchParentSize(),
                fieldBounds = { origin?.let { (if (editThrough) picker.editorBounds else picker.editorTextBounds)?.translate(-it) } },
                triggerBounds = { origin?.let { picker.triggerBounds?.translate(-it) } },
            )
        }
        PickerBubbleLayout(
            picker = picker, face = face, origin = origin, hideForReturn = hideForReturn,
            freezeListSize = suspended && modelList,
            listColor = if (modelList || page == null) colors.surfaceContainerHigh else colors.surface,
            effortColor = if (dark) Color(0xFF2A2B2E) else Color(0xFFF8F8F8),
            effortBorder = Color(0xFF3E3E3E),
            modifier = Modifier.fillMaxSize().testTag("chat-picker-viewport"),
            paneTitle = select,
        ) {
            if (entry == null) {
                val restore = picker.pendingBrowseRestore
                LaunchedEffect(restore, hideForReturn) {
                    if (restore != null && hideForReturn) picker.consumeBrowseRestore(restore)
                }
                Row {
                    PickerBackButton(stringResource(if (modelList) R.string.chat_picker_back_to_controls
                        else R.string.chat_picker_back_to_message), if (modelList) picker::open else picker::dismiss,
                        Modifier.testTag(if (modelList) "chat-model-list-back" else "chat-effort-back"))
                    Text(stringResource(R.string.chat_picker_no_models), Modifier.padding(12.dp),
                        style = MaterialTheme.typography.bodyMedium)
                }
            } else {
                val (model, provider) = entry
                val values = catalog.parameters(model, provider)
                val cap = model.configureCapabilities().find { it.key == picker.optionKey }
                when {
                    modelList || page == ChatModelPickerState.Page.Providers -> {
                        val providers = remember(catalog) { catalog.providers.filter { it.enabled && it.mode == Mode.Chat } }
                        val browsing = providers.find { it.id == picker.browsedProviderId } ?: provider
                        if (!paused && picker.pendingBrowseRestore == null &&
                            page == ChatModelPickerState.Page.Search && browsing.id != picker.browsedProviderId) {
                            LaunchedEffect(browsing.id) { picker.models(browsing.id) }
                        }
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
                                entry.route, picker, dark, paused, hideForReturn, onEditModel = onEditModel, onPick = { next ->
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
                    else -> key(entry.route) {
                        Column(Modifier.verticalScroll(rememberScrollState()).testTag("chat-model-card")) {
                            val speeds = remember(model, provider, values) { model.chatSpeedControls(provider, values) }
                            ChatModelControls(model, values, speeds = speeds, onClose = picker::dismiss, onModels = { picker.models(provider.id, startSearching = true) },
                                onChoose = { capability, choice ->
                                    // Back or a profile change can arrive while the rail still owns a pointer.
                                    if (picker.page == ChatModelPickerState.Page.Controls &&
                                        store.catalog.value.selectedEntry(Mode.Chat)?.route == entry.route) {
                                        store.configure(Mode.Chat, entry.route, model.choose(values, capability.key, choice))
                                    }
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
        }
    }
}

internal val ChatPickerEasing = CubicBezierEasing(0.25f, 0.1f, 0.25f, 1f)

internal data class PickerSpace(val left: Int, val edgeY: Int, val width: Int, val maxHeight: Int, val above: Boolean)

private val PickerGap = 11.5.dp
private val PickerMargin = 12.dp
// A visible model option and the independent search button can share this height.
private val PickerMinListHeight = 104.dp

internal fun Density.pickerComposerMaxHeight(available: Int, safeTop: Int, editorAndToolbar: Int): Int {
    val reserve = safeTop + PickerMargin.roundToPx() + PickerGap.roundToPx() + PickerMinListHeight.roundToPx()
    return (available - reserve).coerceIn(editorAndToolbar.coerceAtMost(available), available)
}

internal fun Density.pickerSpace(
    composer: IntRect, viewport: IntSize, direction: LayoutDirection, reasoning: Boolean = false,
): PickerSpace {
    val sideMargin = minOf(PickerMargin.roundToPx(), viewport.width / 2)
    val preferredWidth = if (reasoning) 288.dp.roundToPx()
        else (viewport.width * 0.6f).roundToInt().coerceIn(240.dp.roundToPx(), 248.dp.roundToPx())
    val width = minOf(preferredWidth, viewport.width - 2 * sideMargin)
    val endAligned = if (direction == LayoutDirection.Ltr) composer.right - width else composer.left
    val left = endAligned.coerceIn(sideMargin, viewport.width - sideMargin - width)

    val above = composer.top.coerceIn(0, viewport.height)
    val below = viewport.height - composer.bottom.coerceIn(0, viewport.height)
    val minimum = PickerMinListHeight.roundToPx()
    val gap = PickerGap.roundToPx()
    val margin = PickerMargin.roundToPx()
    val placeAbove = above >= minimum + gap + margin || above >= below
    val room = if (placeAbove) above else below
    // Give up decorative space first in split screen. The card never borrows editor pixels.
    val spare = (room - minimum).coerceAtLeast(0)
    val actualGap = minOf(gap, spare)
    val maxHeight = room - actualGap - minOf(margin, spare - actualGap)
    val edge = if (placeAbove) above - actualGap else viewport.height - below + actualGap
    return PickerSpace(left, edge, width, maxHeight, placeAbove)
}

@Composable
internal fun PickerBackButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    IconButton(onClick = onClick, modifier = modifier.size(48.dp).focusProperties { canFocus = false }) {
        Icon(Icons.AutoMirrored.Outlined.ArrowBack, label, Modifier.size(19.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun PickerHeading(title: String, color: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Text(title, Modifier.padding(horizontal = 14.dp, vertical = 5.dp).semantics { heading() },
        style = MaterialTheme.typography.labelSmall, color = color)
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
@OptIn(ExperimentalFoundationApi::class)
internal fun PickerOption(label: String, isSelected: Boolean, modifier: Modifier = Modifier, maxLines: Int = 2,
    labelColor: Color = Color.Unspecified, onEditModel: (() -> Unit)? = null, onPick: () -> Unit) {
    val interaction = if (onEditModel == null) {
        Modifier.selectable(isSelected, role = Role.RadioButton, onClick = onPick)
    } else {
        Modifier.semantics { selected = isSelected }.combinedClickable(role = Role.RadioButton,
            onLongClickLabel = stringResource(R.string.chat_picker_edit_model), onLongClick = onEditModel, onClick = onPick)
    }
    Row(modifier.fillMaxWidth().focusProperties { canFocus = false }.then(interaction)
        .heightIn(min = 48.dp).padding(horizontal = 14.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = labelColor,
            maxLines = maxLines, overflow = TextOverflow.Ellipsis)
        if (isSelected) Icon(Icons.Outlined.Check, null, Modifier.size(18.dp))
    }
}
