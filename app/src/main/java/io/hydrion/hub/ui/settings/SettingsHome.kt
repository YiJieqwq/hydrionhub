package io.hydrion.hub.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import io.hydrion.hub.R
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
import androidx.compose.foundation.border
import io.hydrion.hub.ui.chrome.ChatHeaderLayout
import io.hydrion.hub.ui.theme.HubStyle
import io.hydrion.hub.ui.theme.capsuleEdge

internal enum class SettingsEntry { About, Language, Image, Style, Data }

private data class SettingsRow(
    val label: Int,
    val icon: ImageVector,
    val terms: String,
    val destination: SettingsEntry? = null,
)

private val settingsGroups = listOf(
    R.string.settings_general to listOf(
        SettingsRow(R.string.settings_signed_in_accounts, Icons.Outlined.ManageAccounts,
            "登录账户管理 已登录账号 manage signed-in accounts signed in login"),
        SettingsRow(R.string.settings_style, Icons.Outlined.Palette,
            "消息样式 外观与主题 字色 气泡 样式 配色 消息 颜色 message appearance style theme", SettingsEntry.Style),
        SettingsRow(R.string.settings_assistants, Icons.Outlined.PersonOutline, "助手管理 assistants assistant management"),
    ),
    R.string.settings_models to listOf(
        SettingsRow(R.string.settings_chat, Icons.Outlined.ChatBubbleOutline,
            "语言模型供应商 聊天模型供应商 对话模型 供应商 配置 chat models provider reasoning 设置", SettingsEntry.Language),
        SettingsRow(R.string.settings_image, Icons.Outlined.Image,
            "图片模型供应商 图像生成模型供应商 生图 绘图 image generation models provider 设置", SettingsEntry.Image),
        SettingsRow(R.string.settings_defaults, Icons.Outlined.Tune, "默认模型 default models"),
        SettingsRow(R.string.settings_web_search, Icons.Outlined.TravelExplore,
            "网络搜索提供商 网络搜索供应商 web search providers"),
    ),
    R.string.settings_tools to listOf(
        SettingsRow(R.string.settings_memory, Icons.Outlined.Psychology, "记忆 memory"),
        SettingsRow(R.string.settings_environments, Icons.Outlined.Terminal, "容器与环境 containers and environments"),
        SettingsRow(R.string.settings_mcp, Icons.Outlined.Extension, "MCP model context protocol"),
        SettingsRow(R.string.settings_skill, Icons.Outlined.AutoAwesome, "Skill 技能"),
    ),
    R.string.settings_app to listOf(
        SettingsRow(R.string.settings_data, Icons.Outlined.Storage, "数据管理 存储 本机 历史 数据 data management storage history", SettingsEntry.Data),
        SettingsRow(R.string.settings_about, Icons.Outlined.Info, "关于 应用 版本 about version hydrionhub 氢离子", SettingsEntry.About),
    ),
)

@Composable
internal fun SettingsHome(
    search: String, onSearch: (String) -> Unit,
    onOpen: (SettingsEntry) -> Unit, onBack: () -> Unit,
    signedInAccounts: List<SignedInAccountUsage> = emptyList(),
) {
    var searching by rememberSaveable { mutableStateOf(search.isNotBlank()) }
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val active = searching || search.isNotEmpty()
    val hideKeyboard = {
        keyboard?.hide()
        focus.clearFocus(force = true)
    }
    val leaveSearch = {
        onSearch("")
        searching = false
        hideKeyboard()
    }
    val open: (SettingsEntry) -> Unit = { entry ->
        searching = search.isNotBlank()
        hideKeyboard()
        onOpen(entry)
    }
    BackHandler(enabled = active, onBack = leaveSearch)
    val groups = remember(search) {
        val words = search.trim().split(Regex("\\s+")).filter(String::isNotEmpty)
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
    LaunchedEffect(search) { scroll.scrollTo(0) }
    Column(Modifier.fillMaxSize().background(Color.Black)
        .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
        .navigationBarsPadding().imePadding()) {
        SettingsHeader(onBack = { if (active) leaveSearch() else onBack() }, onInfo = { open(SettingsEntry.About) })
        Box(Modifier.fillMaxWidth().weight(1f)) {
            Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (!active) SettingsProfile(signedInAccounts)
                groups.forEach { (title, entries) ->
                    SettingsGroup(stringResource(title), entries, open)
                }
                if (groups.isEmpty()) {
                    Text(stringResource(R.string.settings_no_matches), Modifier.fillMaxWidth().padding(vertical = 20.dp),
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.height(16.dp))
            }
            if (scroll.canScrollForward) {
                Spacer(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(16.dp).background(
                    Brush.verticalGradient(listOf(Color.Transparent, HubStyle.Background))))
            }
        }
        SearchDock(search, active, onSearch, onFocus = { searching = true },
            onSubmit = hideKeyboard, onClose = leaveSearch, modifier = Modifier.fillMaxWidth())
    }
}

private val SettingsButtonSize = 48.dp

@Composable
private fun SettingsHeader(onBack: () -> Unit, onInfo: () -> Unit) {
    Box(Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 4.dp).heightIn(min = 48.dp), contentAlignment = Alignment.Center) {
        Text(stringResource(R.string.settings_title), Modifier.padding(horizontal = 68.dp, vertical = 4.dp),
            fontSize = 18.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
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
            .border(Dp.Hairline, HubStyle.Border, CircleShape), contentAlignment = Alignment.Center) {
            Icon(icon, description, Modifier.size(20.dp), tint = Color.White)
        }
    }
}

@Composable
private fun RoundButton(icon: ImageVector, description: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    IconButton(onClick = onClick, modifier = modifier.size(48.dp).capsuleEdge()
        .clip(CircleShape).background(HubStyle.Raised)) {
        Icon(icon, description, Modifier.size(22.dp), tint = Color.White)
    }
}

@Composable
private fun SettingsGroup(
    title: String, entries: List<SettingsRow>, onOpen: (SettingsEntry) -> Unit
) {
    val shape = RoundedCornerShape(27.dp)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, Modifier.padding(start = 12.dp, top = 6.dp), fontSize = 13.sp, lineHeight = 18.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.Medium)
        Column(Modifier.fillMaxWidth().clip(shape).background(HubStyle.Surface)) {
            entries.forEachIndexed { index, entry ->
                val destination = entry.destination
                val interaction = if (destination == null) {
                    Modifier.semantics(mergeDescendants = true) { disabled() }
                } else {
                    Modifier.clickable(role = Role.Button) { onOpen(destination) }
                }
                val ink = if (destination == null) MaterialTheme.colorScheme.onSurfaceVariant else Color.White
                Row(Modifier.fillMaxWidth().then(interaction)
                    .heightIn(min = 54.dp).padding(start = 18.dp, end = 16.dp, top = 7.dp, bottom = 7.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Icon(entry.icon, null, Modifier.size(20.dp), tint = ink)
                    Text(stringResource(entry.label), Modifier.weight(1f), fontSize = 16.sp, lineHeight = 22.sp,
                        color = ink)
                    if (destination != null) {
                        Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null, Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (index != entries.lastIndex) HorizontalDivider(thickness = Dp.Hairline, color = HubStyle.Border)
            }
        }
    }
}

@Composable
private fun SearchDock(
    search: String, active: Boolean, onSearch: (String) -> Unit, onFocus: () -> Unit,
    onSubmit: () -> Unit, onClose: () -> Unit, modifier: Modifier = Modifier
) {
    val hint = stringResource(R.string.settings_search)
    Row(modifier.padding(start = 34.dp, end = 34.dp, top = 4.dp, bottom = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        BasicTextField(value = search, onValueChange = onSearch, singleLine = true,
            modifier = Modifier.weight(1f).capsuleEdge().clip(CircleShape).background(HubStyle.Raised)
                .onFocusChanged { if (it.isFocused) onFocus() }.semantics { contentDescription = hint },
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = Color.White),
            cursorBrush = SolidColor(Color.White), keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { onSubmit() }), decorationBox = { inner ->
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 16.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Search, null, Modifier.size(21.dp), tint = Color.White)
                    Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                        if (search.isEmpty()) Text(hint, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodyLarge)
                        inner()
                    }
                }
            })
        AnimatedVisibility(visible = active) {
            RoundButton(Icons.Outlined.Close, stringResource(R.string.settings_end_search), onClose)
        }
    }
}
