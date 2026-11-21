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
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.Icon
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
import kotlin.math.roundToInt

internal class ChatModelPickerState {
    enum class Page { Controls, Models, Search, Options }
    enum class SearchScroll { Filter, Submit }

    var page by mutableStateOf<Page?>(null)
        private set
    val modelListNavigation: Boolean
        get() = page == Page.Models || page == Page.Search
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
    var pendingBrowseRestore by mutableStateOf<PickerBrowseSnapshot?>(null)
        private set

    fun restoreBrowsing(snapshot: PickerBrowseSnapshot) {
        page = snapshot.page
        searchQuery = snapshot.query.copy(composition = null)
        pendingSearchScroll = null
        pendingBrowseRestore = snapshot
    }

    fun consumeBrowseRestore(snapshot: PickerBrowseSnapshot) {
        if (pendingBrowseRestore === snapshot) {
            pendingBrowseRestore = null
        }
    }

    private fun show(next: Page?) {
        pendingBrowseRestore = null
        if (next == null || next == Page.Controls || next == Page.Options) {
            searchQuery = TextFieldValue()
        }
        if (next != Page.Search) pendingSearchScroll = null
        page = next
    }
    fun open() = show(Page.Controls)
    fun models() {
        if (page == Page.Controls) show(Page.Search)
    }
    fun clearSearchQuery() {
        if (!modelListNavigation) return
        searchQuery = TextFieldValue()
        pendingSearchScroll = null
    }
    fun updateSearchQuery(value: TextFieldValue) {
        if (!modelListNavigation) return
        if (value.text != searchQuery.text && page != Page.Search) show(Page.Search)
        val term = value.text.trim()
        if (term != searchQuery.text.trim()) pendingSearchScroll = if (term.isEmpty()) null else SearchScroll.Filter
        searchQuery = value
    }
    fun submitSearch() {
        if (!modelListNavigation) return
        if (page != Page.Search) show(Page.Search)
        pendingSearchScroll = SearchScroll.Submit
    }
    fun consumeSearchScroll() { pendingSearchScroll = null }
    fun options(cap: Capability) { optionKey = cap.key; show(Page.Options) }
    fun dismiss() { show(null) }
    fun dismissEffort() {
        if (page == Page.Controls) dismiss()
    }
    fun systemBack() {
        if (page == Page.Options) open() else dismiss()
    }
}

@Composable
internal fun ChatModelPicker(
    store: ProviderStore, picker: ChatModelPickerState,
    suspended: Boolean, hideForReturn: Boolean, modelBrowseProgress: () -> Float,
    modifier: Modifier = Modifier,
) {
    val page = picker.page
    val face = when (page) {
        ChatModelPickerState.Page.Controls -> PickerBubbleFace.Effort
        ChatModelPickerState.Page.Options -> PickerBubbleFace.List
        else -> PickerBubbleFace.Label
    }
    val showing = face != PickerBubbleFace.Label
    val paused = suspended || hideForReturn
    val browseTravel by rememberUpdatedState(modelBrowseProgress)
    val browsePageInFront by remember { derivedStateOf { browseTravel() > 0f } }
    val catalog by store.catalog.collectAsState()
    val entry = remember(catalog) { catalog.selectedEntry(Mode.Chat) }
    val select = stringResource(R.string.chat_controls_select_model)
    val close = stringResource(R.string.chat_picker_close)
    if (showing && !paused) ChatModelPickerBack(onBack = picker::systemBack, imeFirst = browsePageInFront)
    val colors = MaterialTheme.colorScheme
    val dark = colors.onSurface.luminance() > colors.surface.luminance()

    // Let the draft field handle caret placement and taps. Options passes through only its text row.
    val accessibility = if (paused || browsePageInFront) Modifier.clearAndSetSemantics {} else Modifier
    var origin by remember { mutableStateOf<IntOffset?>(null) }
    Box(modifier.then(accessibility).windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Vertical))) {
        // Measure inside the safe insets even while the picker is idle, before its first frame.
        Spacer(Modifier.matchParentSize().onGloballyPositioned { origin = it.positionInWindow().round() })
        if (showing && !paused && !browsePageInFront) {
            val editThrough = page == ChatModelPickerState.Page.Controls
            PickerDismissLayer(
                picker, close, Modifier.matchParentSize(),
                fieldBounds = { origin?.let { (if (editThrough) picker.editorBounds else picker.editorTextBounds)?.translate(-it) } },
                triggerBounds = { origin?.let { picker.triggerBounds?.translate(-it) } },
            )
        }
        PickerBubbleLayout(
            picker = picker, face = face, origin = origin, hideForReturn = hideForReturn,
            modelBrowseProgress = modelBrowseProgress, browsingModels = picker.modelListNavigation,
            listColor = colors.surface,
            effortColor = if (dark) Color(0xFF2A2B2E) else Color(0xFFF8F8F8),
            effortBorder = Color(0xFF3E3E3E),
            modifier = Modifier.fillMaxSize().testTag("chat-picker-viewport"),
            paneTitle = select,
        ) {
            if (entry == null) {
                Text(stringResource(R.string.chat_picker_no_models), Modifier.padding(12.dp),
                    style = MaterialTheme.typography.bodyMedium)
            } else {
                val (model, provider) = entry
                val values = catalog.parameters(model, provider)
                val cap = model.configureCapabilities().find { it.key == picker.optionKey }
                when {
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
                            ChatModelControls(model, values, speeds = speeds, onModels = picker::models,
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
// Reserve room for the scrollable effort or capability controls above the editor.
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
    // Give up decorative space first in split screen. The card stays clear of the editor.
    val spare = (room - minimum).coerceAtLeast(0)
    val actualGap = minOf(gap, spare)
    val maxHeight = room - actualGap - minOf(margin, spare - actualGap)
    val edge = if (placeAbove) above - actualGap else viewport.height - below + actualGap
    return PickerSpace(left, edge, width, maxHeight, placeAbove)
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
