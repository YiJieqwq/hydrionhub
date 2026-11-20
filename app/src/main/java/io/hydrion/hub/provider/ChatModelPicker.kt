package io.hydrion.hub.provider

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.rememberTransition
import androidx.compose.animation.core.tween
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
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.rememberGraphicsLayer
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
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import io.hydrion.hub.R
import io.hydrion.hub.model.Mode
import kotlin.math.roundToInt

internal class ChatModelPickerState {
    enum class Page { Controls, Models, Search, Providers, Options }

    val searchFocus = ChatPickerSearchFocus()

    var page by mutableStateOf<Page?>(null)
        private set
    var optionKey by mutableStateOf("")
        private set
    var composerBounds by mutableStateOf<IntRect?>(null)
    var triggerBounds by mutableStateOf<IntRect?>(null)
    var searchFieldBounds by mutableStateOf<IntRect?>(null)
    var searchQuery by mutableStateOf(TextFieldValue())
        private set
    var searchSubmissions by mutableIntStateOf(0)
        private set
    var browsedProviderId by mutableStateOf<String?>(null)
        private set

    private fun show(next: Page?) {
        if (next == null || next == Page.Controls || next == Page.Options) {
            searchQuery = TextFieldValue()
        } else if (page == Page.Search && next != Page.Search && searchQuery.composition != null) {
            // Keep the query and selection while browsing; composition belongs to the old IME session.
            searchQuery = searchQuery.copy(composition = null)
        }
        if (page == Page.Search && next != Page.Search) {
            searchSubmissions = 0
            searchFieldBounds = null
        }
        page = next
    }
    fun open() { browsedProviderId = null; show(Page.Controls) }
    fun models(providerId: String, startSearching: Boolean = false) {
        if (startSearching && page != Page.Controls) return
        browsedProviderId = providerId
        if (startSearching) beginSearch() else show(Page.Models)
    }
    fun search() {
        if (page == Page.Models) beginSearch()
    }
    private fun beginSearch() {
        searchFocus.capture()
        show(Page.Search)
    }
    fun updateSearchQuery(value: TextFieldValue) {
        if (page == Page.Search) searchQuery = value
    }
    fun submitSearch() {
        if (page == Page.Search) searchSubmissions++
    }
    fun providers() { show(Page.Providers) }
    fun options(cap: Capability) { optionKey = cap.key; show(Page.Options) }
    fun dismiss() { show(null) }
    fun back() {
        show(when (page) {
            Page.Providers, Page.Search -> Page.Models
            Page.Models, Page.Options -> Page.Controls
            else -> null
        })
    }
}

@Composable
internal fun ChatModelPicker(store: ProviderStore, picker: ChatModelPickerState, modifier: Modifier = Modifier) {
    val page = picker.page
    val controls = page == null || page == ChatModelPickerState.Page.Controls
    val visibility = if (controls) {
        val visible = remember(picker) { MutableTransitionState(false) }
        visible.targetState = page != null
        if (page == null && visible.isIdle) return
        rememberTransition(visible, label = "Reasoning panel").animateFloat(
            transitionSpec = { tween(durationMillis = 180, easing = PanelEasing) },
            label = "Visibility",
        ) { if (it) 1f else 0f }
    } else null
    val closing = page == null
    val drawing = if (controls) rememberGraphicsLayer() else null
    val motion = if (visibility == null) Modifier else Modifier.graphicsLayer {
        val hidden = 1f - visibility.value
        alpha = visibility.value
        scaleX = 1f - 0.02f * hidden
        scaleY = scaleX
        translationY = 5.dp.toPx() * hidden
        transformOrigin = TransformOrigin(0f, 1f)
    }
    val catalog by store.catalog.collectAsState()
    val models = remember(catalog) { catalog.entries(Mode.Chat) }
    val entry = catalog.selectedEntry(Mode.Chat)
    val select = stringResource(R.string.chat_controls_select_model)
    val close = stringResource(R.string.chat_picker_close)
    if (!closing) ChatModelPickerBack(picker::back)
    val colors = MaterialTheme.colorScheme
    val dark = colors.onSurface.luminance() > colors.surface.luminance()
    var peak by remember(entry?.route) { mutableStateOf(false) }

    // The picker and draft share a window. Only an explicitly opened search field borrows focus.
    Box(modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Vertical))) {
        var origin by remember { mutableStateOf<IntOffset?>(null) }
        if (page == ChatModelPickerState.Page.Search) {
            SearchDismissLayer(
                picker, close, Modifier.matchParentSize(),
                fieldBounds = { origin?.let { picker.searchFieldBounds?.translate(-it) } },
            )
        } else if (!closing) Box(Modifier.matchParentSize().focusProperties { canFocus = false }
            .clickable(interactionSource = null, indication = null, role = Role.Button, onClick = picker::dismiss)
            .semantics { contentDescription = close })
        Layout(
            modifier = Modifier.fillMaxSize().testTag("chat-picker-viewport")
                .onGloballyPositioned { origin = it.positionInWindow().round() },
            content = {
                val capture = if (drawing == null) Modifier else Modifier.drawWithContent {
                    drawing.record { this@drawWithContent.drawContent() }
                    drawLayer(drawing)
                }
                val border = when {
                    !controls -> BorderStroke(0.66.dp, colors.outlineVariant)
                    peak -> BorderStroke(0.5.dp, if (dark) Color(0x73B085FF) else Color(0x47914EF6))
                    else -> BorderStroke(0.5.dp, (if (dark) Color.White else Color.Black).copy(alpha = 0.05f))
                }
                Surface(shape = RoundedCornerShape(14.dp),
                    color = if (!controls) colors.surface else if (dark) Color(0xFF2A2B2E) else Color(0xFFF8F8F8),
                    border = border, shadowElevation = 6.dp,
                    modifier = motion.then(capture).semantics { paneTitle = select }) {
                    if (entry == null) {
                        Text(stringResource(R.string.chat_picker_no_models), Modifier.padding(16.dp),
                            style = MaterialTheme.typography.bodyMedium)
                    } else {
                        val (model, provider) = entry
                        val values = catalog.parameters(model, provider)
                        val cap = model.configureCapabilities().find { it.key == picker.optionKey }
                        when {
                            page == ChatModelPickerState.Page.Models || page == ChatModelPickerState.Page.Search ||
                                page == ChatModelPickerState.Page.Providers -> {
                                val providers = remember(catalog) { catalog.providers.filter { it.enabled && it.mode == Mode.Chat } }
                                val browsing = providers.find { it.id == picker.browsedProviderId } ?: provider
                                if (page == ChatModelPickerState.Page.Search && browsing.id != picker.browsedProviderId) {
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
                                        entry.route, picker, onPick = { next ->
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
                                    ChatModelControls(model, values, speeds = speeds, onModels = { picker.models(provider.id, startSearching = true) },
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
                                        }, onPeakChanged = { peak = it })
                                }
                            }
                        }
                    }
                }
                // On exit draw the retained layer only. The unplaced interactive card cannot
                // intercept a touch or Back meant for the editor or for a newly opened panel.
                if (closing && drawing != null) Spacer(motion.drawBehind { drawLayer(drawing) })
            },
        ) { children, limits ->
            val windowOrigin = origin
            val anchor = if (controls) picker.triggerBounds else picker.composerBounds
            if (windowOrigin == null || anchor == null) {
                // The safe viewport can start below a status bar or cutout. Wait for its coordinates
                // before placing the first visible frame.
                layout(limits.maxWidth, limits.maxHeight) {}
            } else {
                val viewport = IntSize(limits.maxWidth, limits.maxHeight)
                val localAnchor = anchor.translate(-windowOrigin)
                val space = if (controls) reasoningPanelSpace(localAnchor, viewport)
                    else {
                        // The model list also needs room for its independent search control.
                        val minimum = if (page == ChatModelPickerState.Page.Models ||
                            page == ChatModelPickerState.Page.Search) 104.dp else 88.dp
                        pickerSpace(localAnchor, viewport, layoutDirection, minimum)
                    }
                val card = children.first().measure(Constraints(minWidth = space.width, maxWidth = space.width,
                    maxHeight = space.maxHeight))
                val y = if (space.above) space.edgeY - card.height else space.edgeY
                val visibleCard = if (closing) children.last().measure(Constraints.fixed(card.width, card.height)) else card
                layout(viewport.width, viewport.height) { visibleCard.place(space.left, y) }
            }
        }
    }
}

private val PanelEasing = CubicBezierEasing(0.25f, 0.1f, 0.25f, 1f)

internal data class PickerSpace(val left: Int, val edgeY: Int, val width: Int, val maxHeight: Int, val above: Boolean)

// The reasoning control follows its trigger; the drill-down lists keep their composer anchor.
internal fun Density.reasoningPanelSpace(trigger: IntRect, viewport: IntSize): PickerSpace {
    val margin = 12.dp.roundToPx()
    val insetX = minOf(margin, viewport.width / 2)
    val insetY = minOf(margin, viewport.height / 2)
    val width = minOf(272.dp.roundToPx(), viewport.width - 2 * insetX)
    val left = (trigger.center.x - width / 2).coerceIn(insetX, viewport.width - insetX - width)
    val gap = 8.dp.roundToPx()
    val upperEdge = (trigger.top - gap).coerceIn(insetY, viewport.height - insetY)
    val lowerEdge = (trigger.bottom + gap).coerceIn(insetY, viewport.height - insetY)
    val roomAbove = upperEdge - insetY
    val roomBelow = viewport.height - insetY - lowerEdge
    // Desktop always opens upward. Flip only if a short mobile viewport cannot fit one touch row.
    val row = minOf(48.dp.roundToPx(), viewport.height)
    return if (roomAbove >= row || roomAbove >= roomBelow) {
        PickerSpace(left, maxOf(upperEdge, row), width, maxOf(roomAbove, row), true)
    } else {
        PickerSpace(left, minOf(lowerEdge, viewport.height - row), width, maxOf(roomBelow, row), false)
    }
}

internal fun Density.pickerSpace(
    composer: IntRect, viewport: IntSize, direction: LayoutDirection, minimumHeight: Dp = 88.dp,
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
    val oneRow = minOf(minimumHeight.roundToPx(), viewport.height)
    return if (above >= 96.dp.roundToPx() || above >= below) {
        PickerSpace(left, maxOf(upperEdge, oneRow), width, maxOf(above, oneRow), true)
    } else {
        PickerSpace(left, minOf(lowerEdge, viewport.height - oneRow), width, maxOf(below, oneRow), false)
    }
}

@Composable
internal fun PickerHeading(title: String) {
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
internal fun PickerOption(label: String, selected: Boolean, modifier: Modifier = Modifier, maxLines: Int = 2, onPick: () -> Unit) {
    Row(modifier.fillMaxWidth().focusProperties { canFocus = false }
        .selectable(selected, role = Role.RadioButton, onClick = onPick)
        .heightIn(min = 48.dp).padding(horizontal = 14.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium,
            maxLines = maxLines, overflow = TextOverflow.Ellipsis)
        if (selected) Icon(Icons.Outlined.Check, null, Modifier.size(18.dp))
    }
}
