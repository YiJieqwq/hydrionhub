package io.hydrion.hub.provider

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.hydrion.hub.R
import io.hydrion.hub.model.RemoteModel
import io.hydrion.hub.model.Mode

@Composable
internal fun ModelSettingsFrame(title: String, onBack: () -> Unit, action: @Composable () -> Unit = {},
    content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface).safeDrawingPadding().imePadding()) {
        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.settings_back), Modifier.size(22.dp)) }
            Text(title, Modifier.weight(1f), fontSize = 20.sp, fontWeight = FontWeight.Medium,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            action()
        }
        content()
    }
}

@Composable
internal fun ModelSettingsCard(radius: Dp = 12.dp, content: @Composable ColumnScope.() -> Unit) {
    val colors = MaterialTheme.colorScheme
    val dark = colors.surface.luminance() < .5f
    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(radius),
        color = Color.White.copy(alpha = if (dark) .10f else .96f).compositeOver(colors.surface),
        border = BorderStroke(.6.dp, colors.outlineVariant.copy(alpha = if (dark) .08f else .06f))) {
        Column(content = content)
    }
}

@Composable
internal fun ModelSettingsRow(label: String, onClick: () -> Unit, leading: @Composable () -> Unit,
    trailing: @Composable () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).heightIn(min = 48.dp)
        .padding(horizontal = 12.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(36.dp), contentAlignment = Alignment.Center) { leading() }
        Spacer(Modifier.width(12.dp))
        Text(label, Modifier.weight(1f), fontSize = 15.sp, fontWeight = FontWeight.Medium,
            maxLines = 2, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.width(8.dp))
        trailing()
        Icon(painterResource(R.drawable.lucide_chevron_right), null, Modifier.padding(start = 8.dp).size(16.dp))
    }
}

@Composable
internal fun StatusBadge(label: String, color: Color) {
    Text(label, Modifier.background(color.copy(alpha = .12f), CircleShape).padding(horizontal = 8.dp, vertical = 3.dp),
        fontSize = 11.sp, lineHeight = 15.sp, color = color)
}

@Composable
internal fun providerLabel(provider: Provider): String = when {
    provider.id == "demo-chat" && provider.name == "本机语言模型" -> stringResource(R.string.model_settings_demo_chat)
    provider.id == "demo-image" && provider.name == "本机图片模型" -> stringResource(R.string.model_settings_demo_image)
    else -> provider.name
}

@Composable
internal fun ModelProviderPage(mode: Mode, catalog: ModelCatalog, onBack: () -> Unit,
    onAdd: () -> Unit, onMove: (String, Int) -> Unit, onOpen: (Provider) -> Unit) {
    var ordering by rememberSaveable(mode) { mutableStateOf(false) }
    var query by rememberSaveable(mode) { mutableStateOf("") }
    val all = catalog.providers.filter { it.mode == mode }
    val allLabels = all.map { providerLabel(it) }
    val rows = all.zip(allLabels).filter { (provider, label) ->
        label.contains(query.trim(), true) || provider.name.contains(query.trim(), true) ||
            provider.models.any { it.name.contains(query.trim(), true) || it.id.contains(query.trim(), true) }
    }
    ModelSettingsFrame(stringResource(if (mode == Mode.Chat) R.string.model_settings_chat_providers else R.string.model_settings_image_providers),
        onBack, action = {
            if (mode == Mode.Chat && all.size > 1 && query.isBlank()) IconButton({ ordering = !ordering }) {
                Icon(if (ordering) Icons.Outlined.Check else Icons.Outlined.Sort, stringResource(R.string.chat_provider_order))
            }
            IconButton(onAdd) { Icon(Icons.Outlined.Add, stringResource(R.string.model_settings_add_provider), Modifier.size(22.dp)) } }) {
        OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
            singleLine = true, shape = RoundedCornerShape(12.dp), leadingIcon = { Icon(Icons.Outlined.Search, null) },
            placeholder = { Text(stringResource(R.string.model_settings_filter)) })
        LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp)) {
            if (rows.isEmpty()) item { Text(stringResource(if (all.isEmpty()) R.string.model_settings_empty else R.string.settings_no_matches)) }
            itemsIndexed(rows, key = { _, pair -> pair.first.id }) { index, (provider, label) ->
                // The list scrolls as one card, with only the outside corners rounded.
                val shape = RoundedCornerShape(topStart = if (index == 0) 12.dp else 0.dp, topEnd = if (index == 0) 12.dp else 0.dp,
                    bottomStart = if (index == rows.lastIndex) 12.dp else 0.dp, bottomEnd = if (index == rows.lastIndex) 12.dp else 0.dp)
                Surface(shape = shape, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                    Column {
                        ModelSettingsRow(label, { onOpen(provider) }, { BrowseAvatar(provider, null, 22.dp, browseColors()) }) {
                            val enabled = provider.enabled
                            val dark = browseColors().dark
                            StatusBadge(stringResource(if (enabled) R.string.model_settings_enabled else R.string.model_settings_disabled),
                                when {
                                    enabled && dark -> Color(0xff81c784)
                                    enabled -> Color(0xff2e7d32)
                                    dark -> Color(0xffffb74d)
                                    else -> Color(0xff875000)
                                })
                        }
                        if (ordering && query.isBlank()) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            IconButton({ onMove(provider.id, -1) }, enabled = index > 0) {
                                Icon(Icons.Outlined.KeyboardArrowUp, stringResource(R.string.chat_provider_up))
                            }
                            IconButton({ onMove(provider.id, 1) }, enabled = index < rows.lastIndex) {
                                Icon(Icons.Outlined.KeyboardArrowDown, stringResource(R.string.chat_provider_down))
                            }
                        }
                        if (index < rows.lastIndex) HorizontalDivider(thickness = .6.dp, color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .4f))
                    }
                }
            }
            if (mode == Mode.Chat && query.isBlank() && all.none { it.id == DEEPSEEK_PROVIDER_ID }) item {
                TextButton({ onOpen(deepSeekProvider) }) { Text(stringResource(R.string.model_settings_add_deepseek)) }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DefaultModelsPage(store: ProviderStore, catalog: ModelCatalog, onBack: () -> Unit) {
    var picking by rememberSaveable { mutableStateOf<Mode?>(null) }
    ModelSettingsFrame(stringResource(R.string.settings_defaults), onBack) {
        LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            items(Mode.entries.toList(), key = { it.name }) { mode ->
                val rows = catalog.entries(mode)
                val current = catalog.defaults[mode] ?: rows.firstOrNull()?.route
                val entry = rows.find { it.route == current }
                ModelSettingsCard(16.dp) {
                    Column(Modifier.padding(14.dp)) {
                        Row(Modifier.heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(if (mode == Mode.Chat) Icons.Outlined.ChatBubbleOutline else Icons.Outlined.Image, null, Modifier.size(18.dp))
                            Text(stringResource(if (mode == Mode.Chat) R.string.settings_chat else R.string.settings_image),
                                Modifier.weight(1f).padding(start = 8.dp), fontSize = 15.sp, fontWeight = FontWeight.Medium)
                            val first = rows.firstOrNull()
                            if (first != null && first.route != current) {
                                IconButton({ store.setDefault(mode, first.route) }) {
                                    Icon(Icons.Outlined.Restore, stringResource(R.string.model_settings_reset), Modifier.size(20.dp))
                                }
                            }
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(stringResource(R.string.model_settings_new_only), fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = .7f))
                        Spacer(Modifier.height(12.dp))
                        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = .10f))
                            .clickable(enabled = rows.isNotEmpty()) { picking = mode }
                            .heightIn(min = 48.dp).padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (entry != null) BrowseAvatar(entry.second, entry.first, 24.dp, browseColors())
                            val fallback = if (rows.isEmpty()) R.string.model_settings_no_models else R.string.model_settings_unavailable
                            Text(entry?.let { it.first.name + " · " + providerLabel(it.second) } ?: stringResource(fallback),
                                Modifier.weight(1f), fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            if (rows.isNotEmpty()) Icon(painterResource(R.drawable.lucide_chevron_right), null, Modifier.size(16.dp))
                        }
                    }
                }
                if (mode == Mode.Chat) {
                    Spacer(Modifier.height(12.dp))
                    ModelSettingsCard(16.dp) {
                        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f).padding(end = 12.dp)) {
                                Text(stringResource(R.string.chat_model_per_conversation))
                                Text(stringResource(R.string.chat_model_per_conversation_hint), style = MaterialTheme.typography.bodySmall)
                            }
                            Switch(catalog.perChatModel, store::setPerChatModel)
                        }
                    }
                }
            }
        }
    }
    picking?.let { mode ->
        ModelSelectionSheet(catalog.entries(mode), catalog.initialRoutes()[mode], onDismiss = { picking = null }) { route ->
            if (store.setDefault(mode, route)) picking = null
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ModelSelectionSheet(entries: List<Pair<ProviderModel, Provider>>, selected: RemoteModel?,
    onDismiss: () -> Unit, onPick: (RemoteModel) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    val labels = modelGroupLabels(entries)
    val rows = remember(entries, labels, query) { modelMenuRows(entries, query, labels) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth().padding(horizontal = 16.dp), singleLine = true,
            leadingIcon = { Icon(Icons.Outlined.Search, null) }, label = { Text(stringResource(R.string.chat_picker_search_hint)) })
        LazyColumn(contentPadding = PaddingValues(top = 8.dp, bottom = 20.dp)) {
            if (rows.isEmpty()) item { Text(stringResource(if (entries.isEmpty()) R.string.model_settings_no_models
                else R.string.chat_picker_search_empty), Modifier.padding(16.dp)) }
            modelMenuItems(rows, selected, onPick)
        }
    }
}
