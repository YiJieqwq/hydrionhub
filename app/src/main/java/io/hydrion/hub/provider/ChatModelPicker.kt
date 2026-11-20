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
import androidx.compose.ui.platform.LocalLayoutDirection
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

    var page by mutableStateOf<Page?>(null)
        private set
    var optionKey by mutableStateOf("")
        private set
    var composerBounds by mutableStateOf<IntRect?>(null)
    var editorBounds by mutableStateOf<IntRect?>(null)
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
        if (page == Page.Search && next != Page.Search) searchSubmissions = 0
        page = next
    }
    fun open() { browsedProviderId = null; show(Page.Controls) }
    fun models(providerId: String, startSearching: Boolean = false) {
        if (startSearching && page != Page.Controls) return
        browsedProviderId = providerId
        show(if (startSearching) Page.Search else Page.Models)
    }
    fun search() {
        if (page == Page.Models) show(Page.Search)
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
    val modelList = page == ChatModelPickerState.Page.Models || page == ChatModelPickerState.Page.Search
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
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val motion = if (visibility == null) Modifier else Modifier.graphicsLayer {
        val hidden = 1f - visibility.value
        alpha = visibility.value
        scaleX = 1f - 0.02f * hidden
        scaleY = scaleX
        transformOrigin = TransformOrigin(if (rtl) 0f else 1f, 1f)
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

    // Controls and search both leave the composer editor reachable through the dismissal layer.
    Box(modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Vertical))) {
        var origin by remember { mutableStateOf<IntOffset?>(null) }
        if (page == ChatModelPickerState.Page.Controls || page == ChatModelPickerState.Page.Search) {
            PickerDismissLayer(
                picker, close, Modifier.matchParentSize(),
                fieldBounds = { origin?.let { picker.editorBounds?.translate(-it) } },
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
                    color = when {
                        modelList -> colors.surfaceContainerHigh
                        !controls -> colors.surface
                        dark -> Color(0xFF2A2B2E)
                        else -> Color(0xFFF8F8F8)
                    },
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
                            modelList || page == ChatModelPickerState.Page.Providers -> {
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
            val anchor = picker.composerBounds
            if (windowOrigin == null || anchor == null) {
                // The safe viewport can start below a status bar or cutout. Wait for its coordinates
                // before placing the first visible frame.
                layout(limits.maxWidth, limits.maxHeight) {}
            } else {
                val viewport = IntSize(limits.maxWidth, limits.maxHeight)
                val localAnchor = anchor.translate(-windowOrigin)
                val space = pickerSpace(localAnchor, viewport, layoutDirection, reasoning = controls)
                val card = children.first().measure(Constraints(minWidth = space.width, maxWidth = space.width,
                    maxHeight = space.maxHeight))
                val y = if (space.above) space.edgeY - card.height else space.edgeY
                val visibleCard = if (closing) children.last().measure(Constraints.fixed(card.width, card.height)) else card
                val gap = if (space.above) localAnchor.top - space.edgeY else space.edgeY - localAnchor.bottom
                val travel = minOf(5.dp.toPx(), gap.coerceAtLeast(0).toFloat())
                layout(viewport.width, viewport.height) {
                    val shift = (travel * (1f - (visibility?.value ?: 1f))).roundToInt()
                    visibleCard.place(space.left, if (space.above) y + shift else y - shift)
                }
            }
        }
    }
}

private val PanelEasing = CubicBezierEasing(0.25f, 0.1f, 0.25f, 1f)

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
