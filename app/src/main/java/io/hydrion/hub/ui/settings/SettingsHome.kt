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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import io.hydrion.hub.ui.chrome.ChatHeaderLayout
import io.hydrion.hub.ui.theme.HubStyle
import io.hydrion.hub.ui.theme.capsuleEdge

internal enum class SettingsEntry(val title: String, val terms: String) {
    About("关于 HydrionHub", "关于 应用 版本 about version 氢离子"),
    Language("聊天模型", "语言模型供应商 聊天模型供应商 对话 配置 chat provider reasoning 设置"),
    Image("图像生成模型", "图片模型供应商 图像生成模型供应商 生图 绘图 image provider 设置"),
    Style("消息样式", "外观与主题 字色 气泡 样式 配色 消息 颜色 appearance style theme"),
    Data("数据管理", "存储 本机 历史 数据 storage history")
}

private val settingsGroups = listOf(
    "模型" to listOf(SettingsEntry.Language, SettingsEntry.Image),
    "应用" to listOf(SettingsEntry.Style, SettingsEntry.Data, SettingsEntry.About)
)

@Composable
internal fun SettingsHome(
    languageCount: Int, imageCount: Int, search: String, onSearch: (String) -> Unit,
    onOpen: (SettingsEntry) -> Unit, onBack: () -> Unit
) {
    val context = LocalContext.current
    val version = remember(context) { context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty() }
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
                (entry != SettingsEntry.About || words.isNotEmpty()) &&
                    words.all { (entry.title + " " + entry.terms).contains(it, ignoreCase = true) }
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
        Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(scroll).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(15.dp)) {
            groups.forEach { (title, entries) ->
                SettingsGroup(title, entries, languageCount, imageCount, version, open)
            }
            if (groups.isEmpty()) {
                Text("没有匹配的设置", Modifier.fillMaxWidth().padding(vertical = 20.dp),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(6.dp))
        }
        SearchDock(search, active, onSearch, onFocus = { searching = true },
            onSubmit = hideKeyboard, onClose = leaveSearch, modifier = Modifier.fillMaxWidth())
    }
}

private val SettingsButtonSize = 48.dp

@Composable
private fun SettingsHeader(onBack: () -> Unit, onInfo: () -> Unit) {
    val hitSlop = (SettingsButtonSize - ChatHeaderLayout.ActionSize) / 2
    Box(Modifier.fillMaxWidth().heightIn(min = 62.dp)) {
        Text("设置", Modifier.align(Alignment.Center).padding(horizontal = 14.dp, vertical = 5.dp),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold, color = Color.White)
        Row(Modifier.align(Alignment.TopCenter).widthIn(max = ChatHeaderLayout.MaxWidth).fillMaxWidth()
            .padding(start = ChatHeaderLayout.SidePadding - hitSlop, top = ChatHeaderLayout.TopPadding - hitSlop,
                end = ChatHeaderLayout.SidePadding - hitSlop),
            horizontalArrangement = Arrangement.SpaceBetween) {
            HeaderButton(Icons.AutoMirrored.Outlined.ArrowBack, "返回", onBack, "settings-back-circle")
            HeaderButton(Icons.Outlined.Info, "关于应用", onInfo, "settings-info-circle")
        }
    }
}

@Composable
private fun HeaderButton(icon: ImageVector, description: String, onClick: () -> Unit, circleTag: String) {
    IconButton(onClick = onClick, modifier = Modifier.size(SettingsButtonSize)) {
        Box(Modifier.size(ChatHeaderLayout.ActionSize).testTag(circleTag).capsuleEdge().clip(CircleShape)
            .background(HubStyle.Raised),
            contentAlignment = Alignment.Center) {
            Icon(icon, description, Modifier.size(22.dp), tint = Color.White)
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
    title: String, entries: List<SettingsEntry>, languageCount: Int, imageCount: Int, version: String,
    onOpen: (SettingsEntry) -> Unit
) {
    val shape = RoundedCornerShape(22.dp)
    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Text(title, Modifier.padding(start = 5.dp, top = 8.dp), color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Medium)
        Column(Modifier.fillMaxWidth().capsuleEdge(shape).clip(shape).background(HubStyle.Raised)) {
            entries.forEachIndexed { index, entry ->
                val (icon, detail) = when (entry) {
                    SettingsEntry.Language -> Icons.Outlined.ChatBubbleOutline to "${languageCount} 个供应商"
                    SettingsEntry.Image -> Icons.Outlined.Image to "${imageCount} 个供应商"
                    SettingsEntry.Style -> Icons.Outlined.Palette to "主题、字色与消息气泡"
                    SettingsEntry.Data -> Icons.Outlined.Storage to "本机会话与配置"
                    SettingsEntry.About -> Icons.Outlined.Info to if (version.isEmpty()) "应用信息" else "版本 $version"
                }
                Row(Modifier.fillMaxWidth().clickable(role = Role.Button) { onOpen(entry) }
                    .heightIn(min = 66.dp).padding(horizontal = 15.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(13.dp)) {
                    Icon(icon, null, Modifier.size(22.dp), tint = Color.White)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                        Text(entry.title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, color = Color.White)
                        Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null, Modifier.size(20.dp), tint = Color(0xFFAAAAAA))
                }
                if (index != entries.lastIndex) HorizontalDivider(Modifier.padding(start = 50.dp, end = 14.dp),
                    thickness = .6.dp, color = Color(0xFF474747))
            }
        }
    }
}

@Composable
private fun SearchDock(
    search: String, active: Boolean, onSearch: (String) -> Unit, onFocus: () -> Unit,
    onSubmit: () -> Unit, onClose: () -> Unit, modifier: Modifier = Modifier
) {
    Row(modifier.padding(start = 15.dp, end = 15.dp, top = 7.dp, bottom = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        BasicTextField(value = search, onValueChange = onSearch, singleLine = true,
            modifier = Modifier.weight(1f).capsuleEdge().clip(CircleShape).background(HubStyle.Raised)
                .onFocusChanged { if (it.isFocused) onFocus() }.semantics { contentDescription = "搜索设置" },
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = Color.White),
            cursorBrush = SolidColor(Color.White), keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { onSubmit() }), decorationBox = { inner ->
                Row(Modifier.fillMaxWidth().heightIn(min = 50.dp).padding(horizontal = 16.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Search, null, Modifier.size(21.dp), tint = Color.White)
                    Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                        if (search.isEmpty()) Text("搜索设置", color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodyLarge)
                        inner()
                    }
                }
            })
        AnimatedVisibility(visible = active) {
            RoundButton(Icons.Outlined.Close, "退出搜索", onClose)
        }
    }
}
