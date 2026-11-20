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

internal class ChatModelPickerState {
    enum class Page { Controls, Models, Options }

    var page by mutableStateOf<Page?>(null)
        private set
    var optionKey by mutableStateOf("")
        private set
    var anchor by mutableStateOf(IntRect.Zero)
    var composer by mutableStateOf(IntRect.Zero)

    fun open() { page = Page.Controls }
    fun models() { page = Page.Models }
    fun options(cap: Capability) { optionKey = cap.key; page = Page.Options }
    fun dismiss() { page = null }
    fun back() {
        page = if (page == Page.Models || page == Page.Options) Page.Controls else null
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
                            page == ChatModelPickerState.Page.Models -> {
                                val nameCounts = remember(models) { models.groupingBy { it.first.name }.eachCount() }
                                val default = models.find { it.route == catalog.defaults[Mode.Chat] }
                                PickerList(select, Modifier.testTag("chat-model-list"),
                                    selectedIndex = models.indexOf(entry) + if (default == null) 0 else 1) {
                                    if (default != null) item {
                                        Column(Modifier.fillMaxWidth().focusProperties { canFocus = false }
                                            .clickable(role = Role.Button) {
                                                store.configure(Mode.Chat, default.route, catalog.parameters(default.first, emptyMap()))
                                                picker.open()
                                            }.heightIn(min = 48.dp).padding(horizontal = 14.dp, vertical = 6.dp),
                                            verticalArrangement = Arrangement.Center) {
                                            Text(stringResource(R.string.chat_picker_default), style = MaterialTheme.typography.bodyMedium)
                                            Text(default.first.name, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                    }
                                    items(models) { item ->
                                        val route = item.route
                                        val label = if (nameCounts[item.first.name] == 1) item.first.name
                                            else "${item.first.name} · ${item.second.name}"
                                        PickerOption(label, route == entry.route,
                                            Modifier.testTag("chat-model-option:${route.providerId}/${route.modelId}")) {
                                            if (route != entry.route) store.configure(Mode.Chat, route, catalog.parameters(item.first, item.second))
                                            picker.open()
                                        }
                                    }
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
                                ChatModelControls(model, values, onModels = picker::models,
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
            if (windowOrigin == null) {
                // The safe viewport can start below a status bar or cutout. Wait for its coordinates
                // before placing the first visible frame.
                layout(limits.maxWidth, limits.maxHeight) {}
            } else {
                val viewport = IntSize(limits.maxWidth, limits.maxHeight)
                // Align horizontally to the chip and keep the whole draft visible below the panel.
                val chip = picker.anchor
                val capsule = picker.composer
                val anchor = IntRect(chip.left, minOf(chip.top, capsule.top), chip.right, maxOf(chip.bottom, capsule.bottom))
                val space = pickerSpace(anchor.translate(-windowOrigin), viewport, 8.dp.roundToPx(),
                    244.dp.roundToPx(), 112.dp.roundToPx(), layoutDirection)
                val card = children.single().measure(Constraints(minWidth = space.width, maxWidth = space.width,
                    maxHeight = space.maxHeight))
                val y = if (space.above) space.edgeY - card.height else space.edgeY
                layout(viewport.width, viewport.height) { card.place(space.left, y) }
            }
        }
    }
}

internal data class PickerSpace(val left: Int, val edgeY: Int, val width: Int, val maxHeight: Int, val above: Boolean)

internal fun pickerSpace(
    anchor: IntRect, viewport: IntSize, gap: Int, preferredWidth: Int, preferredHeight: Int, direction: LayoutDirection,
): PickerSpace {
    val margin = minOf(gap, viewport.width / 2, viewport.height / 2)
    val width = minOf(preferredWidth, viewport.width - 2 * margin)
    val x = if (direction == LayoutDirection.Ltr) anchor.right - width else anchor.left
    val left = x.coerceIn(margin, viewport.width - margin - width)
    val upperEdge = (anchor.top - gap).coerceIn(margin, viewport.height - margin)
    val lowerEdge = (anchor.bottom + gap).coerceIn(margin, viewport.height - margin)
    val above = upperEdge - margin
    val below = viewport.height - margin - lowerEdge
    return if (above >= preferredHeight || above >= below) PickerSpace(left, upperEdge, width, above, true)
        else PickerSpace(left, lowerEdge, width, below, false)
}

@Composable
private fun PickerList(
    title: String, modifier: Modifier = Modifier, selectedIndex: Int,
    items: LazyListScope.() -> Unit,
) {
    Column(modifier.padding(vertical = 6.dp)) {
        Text(title, Modifier.padding(horizontal = 14.dp, vertical = 5.dp).semantics { heading() },
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
