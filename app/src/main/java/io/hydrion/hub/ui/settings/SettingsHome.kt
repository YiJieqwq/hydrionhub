package io.hydrion.hub.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.annotation.DrawableRes
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import io.hydrion.hub.R
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.hydrion.hub.ui.chrome.ChatHeaderLayout
import io.hydrion.hub.ui.theme.HubStyle
import io.hydrion.hub.ui.messages.ChatEdgeBrushes
import io.hydrion.hub.ui.messages.ChatEdgeFade
import io.hydrion.hub.ui.messages.chatFadeBands
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.dropWhile
import kotlinx.coroutines.flow.first

internal enum class SettingsEntry { About, Language, Image, Style, Data, Backup, Assistants, Defaults, Search }

private data class SettingsRow(
    val label: Int,
    @DrawableRes val icon: Int,
    val terms: String,
    val destination: SettingsEntry? = null,
)

private val settingsGroups = listOf(
    R.string.settings_general to listOf(
        SettingsRow(R.string.settings_signed_in_accounts, R.drawable.lucide_user_round,
            "登录账户管理 已登录账号 manage signed-in accounts signed in login"),
        SettingsRow(R.string.settings_style, R.drawable.lucide_sun_moon,
            "消息样式 外观与主题 字色 气泡 样式 配色 消息 颜色 message appearance style theme", SettingsEntry.Style),
        SettingsRow(R.string.settings_assistants, R.drawable.lucide_bot, "助手管理 assistants assistant management", SettingsEntry.Assistants),
    ),
    R.string.settings_models to listOf(
        SettingsRow(R.string.settings_defaults, R.drawable.lucide_heart, "默认模型配置 default models", SettingsEntry.Defaults),
        SettingsRow(R.string.settings_chat, R.drawable.lucide_boxes,
            "语言模型供应商 聊天模型供应商 对话模型 供应商 配置 chat models provider reasoning 设置", SettingsEntry.Language),
        SettingsRow(R.string.settings_image, R.drawable.lucide_image,
            "图片模型供应商 图像生成模型供应商 生图 绘图 image generation models provider 设置", SettingsEntry.Image),
        SettingsRow(R.string.settings_web_search, R.drawable.lucide_earth,
            "联网搜索配置 网络搜索提供商 网络搜索供应商 web search providers", SettingsEntry.Search),
    ),
    R.string.settings_tools to listOf(
        SettingsRow(R.string.settings_memory, R.drawable.lucide_brain, "记忆 memory"),
        SettingsRow(R.string.settings_environments, R.drawable.lucide_folder_code, "容器与环境 containers and environments"),
        SettingsRow(R.string.settings_mcp, R.drawable.lucide_terminal, "MCP model context protocol"),
        SettingsRow(R.string.settings_skill, R.drawable.lucide_wand_sparkles, "Skill 技能"),
    ),
    R.string.settings_app to listOf(
        SettingsRow(R.string.settings_data, R.drawable.lucide_hard_drive, "数据管理 存储 本机 历史 数据 data management storage history", SettingsEntry.Data),
        SettingsRow(R.string.backup_title, R.drawable.lucide_database, "数据备份 本地导出 恢复 local chat backup export restore", SettingsEntry.Backup),
        SettingsRow(R.string.settings_about, R.drawable.lucide_badge_info, "关于 应用 版本 about version hydrionhub 氢离子", SettingsEntry.About),
    ),
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SettingsHome(
    search: String, onSearch: (String) -> Unit,
    onOpen: (SettingsEntry) -> Unit, onBack: () -> Unit,
    signedInAccounts: List<SignedInAccountUsage> = emptyList(),
) {
    var editing by remember { mutableStateOf(false) }
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val density = LocalDensity.current
    val ime = WindowInsets.ime
    val nav = WindowInsets.navigationBars
    val imeVisible = rememberUpdatedState(WindowInsets.isImeVisible)
    val query = search.trim()
    val hideKeyboard = {
        focus.clearFocus(force = true)
        keyboard?.hide()
        Unit
    }
    val leaveSearch = {
        onSearch("")
        hideKeyboard()
    }
    val open: (SettingsEntry) -> Unit = { entry ->
        hideKeyboard()
        onOpen(entry)
    }
    LaunchedEffect(editing) {
        if (editing) {
            // Ignore the initial hidden frame while the field waits for its keyboard.
            snapshotFlow { imeVisible.value || ime.getBottom(density) > nav.getBottom(density) }
                .awaitSettingsKeyboardDismissal()
            focus.clearFocus()
        }
    }
    BackHandler(enabled = editing || query.isNotEmpty(), onBack = leaveSearch)
    val groups = remember(query) {
        val words = query.split(Regex("\\s+")).filter(String::isNotEmpty)
        settingsGroups.mapNotNull { (section, entries) ->
            val matches = entries.filter { entry ->
                // About is always accessible in the top bar. Include it among explicitly matched results.
                (entry.destination != SettingsEntry.About || words.isNotEmpty()) &&
                    words.all { entry.terms.contains(it, ignoreCase = true) }
            }
            if (matches.isEmpty()) null else section to matches
        }
    }
    val scroll = rememberScrollState()
    LaunchedEffect(query) { scroll.scrollTo(0) }
    val background = MaterialTheme.colorScheme.background
    val brushes = remember(background) { ChatEdgeBrushes(background) }
    val dockBottom = if (editing) 12.dp else 14.5.dp
    Scaffold(
        modifier = Modifier.fillMaxSize().background(background)
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
            .navigationBarsPadding().imePadding(),
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        containerColor = background,
        topBar = {
            SettingsHeader(onBack = { if (editing || query.isNotEmpty()) leaveSearch() else onBack() },
                onInfo = { open(SettingsEntry.About) })
        },
        bottomBar = {
            SearchDock(search, editing, dockBottom, onSearch, onFocus = { focused ->
                editing = focused
                if (!focused && search.isNotEmpty() && query.isEmpty()) onSearch("")
            },
                onSubmit = hideKeyboard, onClose = leaveSearch, modifier = Modifier.fillMaxWidth())
        },
    ) { bars ->
        Column(Modifier.fillMaxSize().settingsFades(bars, dockBottom, brushes, scroll)
            .verticalScroll(scroll).padding(bars).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (query.isEmpty()) SettingsProfile(signedInAccounts)
            groups.forEach { (title, entries) -> SettingsGroup(stringResource(title), entries, open) }
            if (groups.isEmpty()) {
                Text(stringResource(R.string.settings_no_matches), Modifier.fillMaxWidth().padding(vertical = 20.dp),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

internal suspend fun Flow<Boolean>.awaitSettingsKeyboardDismissal() {
    dropWhile { !it }.first { !it }
}

private val DockTopPadding = 4.dp

private fun Modifier.settingsFades(
    bars: PaddingValues, dockBottom: Dp, brushes: ChatEdgeBrushes, scroll: ScrollState,
) = drawWithCache {
    val dockHeight = bars.calculateBottomPadding().toPx()
    val bands = chatFadeBands(size.height, bars.calculateTopPadding().toPx(), dockHeight,
        size.height - dockHeight + DockTopPadding.toPx(), size.height - dockBottom.roundToPx(), density)
    val reveal = ChatEdgeFade.ScrollRevealDp.dp.toPx()
    onDrawWithContent {
        drawContent()
        if (bands.topLength > 0f && scroll.canScrollBackward) {
            drawRect(brushes.top(bands), size = Size(size.width, bands.topLength),
                alpha = (scroll.value / reveal).coerceIn(0f, 1f))
        }
        if (bands.bottomLength > 0f && scroll.canScrollForward) {
            drawRect(brushes.bottom(bands, size.height), Offset(0f, size.height - bands.bottomLength),
                Size(size.width, bands.bottomLength), alpha = ((scroll.maxValue - scroll.value) / reveal).coerceIn(0f, 1f))
        }
    }
}

private val SettingsButtonSize = 48.dp

@Composable
private fun SettingsHeader(onBack: () -> Unit, onInfo: () -> Unit) {
    Box(Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 4.dp).heightIn(min = 48.dp), contentAlignment = Alignment.Center) {
        Text(stringResource(R.string.settings_title), Modifier.padding(horizontal = 68.dp, vertical = 4.dp),
            fontSize = 17.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onBackground)
        Row(Modifier.widthIn(max = ChatHeaderLayout.MaxWidth).fillMaxWidth().padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween) {
            HeaderButton(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.settings_back), onBack, "settings-back-circle")
            HeaderButton(Icons.Outlined.Info, stringResource(R.string.settings_info), onInfo, "settings-info-circle")
        }
    }
}

@Composable
private fun HeaderButton(icon: ImageVector, description: String, onClick: () -> Unit, circleTag: String) {
    IconButton(onClick = onClick, modifier = Modifier.size(SettingsButtonSize)) {
        Box(Modifier.size(44.dp).testTag(circleTag).clip(CircleShape).background(HubStyle.Raised)
            .border(HubStyle.SettingsOutline, CircleShape), contentAlignment = Alignment.Center) {
            Icon(icon, description, Modifier.size(20.dp), tint = Color.White)
        }
    }
}

@Composable
private fun Modifier.searchCapsule(): Modifier {
    val colors = MaterialTheme.colorScheme
    val dark = colors.onSurface.luminance() > colors.surface.luminance()
    return border(HubStyle.SettingsOutline.width, if (dark) HubStyle.ComposerBorder else colors.outlineVariant, CircleShape)
        .clip(CircleShape).background(if (dark) HubStyle.Raised else colors.surfaceContainerHigh)
}

@Composable
private fun searchInk(): Color {
    val colors = MaterialTheme.colorScheme
    return if (colors.onSurface.luminance() > colors.surface.luminance()) Color.White else colors.onSurface
}

@Composable
private fun RoundButton(icon: ImageVector, description: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    IconButton(onClick = onClick, modifier = modifier.size(48.dp).searchCapsule()) {
        Icon(icon, description, Modifier.size(22.dp), tint = searchInk())
    }
}

@Composable
private fun SettingsGroup(
    title: String, entries: List<SettingsRow>, onOpen: (SettingsEntry) -> Unit
) {
    val shape = RoundedCornerShape(27.dp)
    val colors = MaterialTheme.colorScheme
    val dark = colors.onSurface.luminance() > colors.surface.luminance()
    val fill = if (dark) HubStyle.Raised else colors.surfaceContainerHigh
    val line = if (dark) HubStyle.ComposerBorder else colors.outlineVariant
    val stroke = HubStyle.SettingsOutline.width
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, Modifier.padding(start = 12.dp, top = 6.dp), fontSize = 13.sp, lineHeight = 18.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.Medium)
        Column(Modifier.fillMaxWidth().border(stroke, line, shape).clip(shape).background(fill)) {
            entries.forEachIndexed { index, entry ->
                val destination = entry.destination
                val interaction = if (destination == null) {
                    Modifier.semantics(mergeDescendants = true) { disabled() }
                } else {
                    Modifier.clickable(role = Role.Button) { onOpen(destination) }
                }
                val ink = if (destination == null) colors.onSurfaceVariant else if (dark) Color.White else colors.onSurface
                val separator = if (index == entries.lastIndex) Modifier else Modifier.drawBehind {
                    val px = kotlin.math.ceil(stroke.toPx()).coerceAtMost(size.height)
                    drawRect(line, Offset(0f, size.height - px), Size(size.width, px))
                }
                Row(Modifier.fillMaxWidth().then(separator).then(interaction)
                    .heightIn(min = 54.dp).padding(start = 18.dp, end = 16.dp, top = 7.dp, bottom = 7.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Icon(painterResource(entry.icon), null, Modifier.size(20.dp), tint = ink)
                    Text(stringResource(entry.label), Modifier.weight(1f), fontSize = 16.sp, lineHeight = 22.sp,
                        color = ink)
                    if (destination != null) {
                        Icon(painterResource(R.drawable.lucide_chevron_right), null, Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchDock(
    search: String, active: Boolean, bottomGap: Dp, onSearch: (String) -> Unit, onFocus: (Boolean) -> Unit,
    onSubmit: () -> Unit, onClose: () -> Unit, modifier: Modifier = Modifier
) {
    val hint = stringResource(R.string.settings_search)
    val ink = searchInk()
    val side = if (active) 12.dp else 34.dp
    Row(modifier.padding(start = side, end = side, top = DockTopPadding, bottom = bottomGap),
        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        BasicTextField(value = search, onValueChange = onSearch, singleLine = true,
            modifier = Modifier.weight(1f).height(SettingsButtonSize).searchCapsule()
                .onFocusChanged { onFocus(it.isFocused) }.semantics { contentDescription = hint },
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = ink),
            cursorBrush = SolidColor(ink), keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { onSubmit() }), decorationBox = { inner ->
                Row(Modifier.fillMaxSize().padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Search, null, Modifier.size(21.dp), tint = ink)
                    Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                        if (search.isEmpty()) Text(hint, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodyLarge)
                        inner()
                    }
                }
            })
        if (active) {
            RoundButton(Icons.Outlined.Close, stringResource(R.string.settings_end_search), onClose)
        }
    }
}
