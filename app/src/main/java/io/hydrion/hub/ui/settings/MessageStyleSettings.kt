package io.hydrion.hub.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.hydrion.hub.ui.theme.capsuleEdge
import io.hydrion.hub.provider.Header
import io.hydrion.hub.ui.messages.*

internal class StyleEditorState(private val state: SavedStateHandle) : ViewModel() {
    val assistant = state.getStateFlow("assistant", false)
    fun editAssistant(value: Boolean) { state["assistant"] = value }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MessageStyleSettings(onBack: () -> Unit) {
    val store = LocalMessageStyleStore.current
    val style by store.style.collectAsStateWithLifecycle()
    val editor = viewModel<StyleEditorState>()
    val assistant by editor.assistant.collectAsStateWithLifecycle()
    val tone = style.tone
    val editable = style.material != CardMaterial.Default
    val palette = if (editable) style.palette else style.renderPalette
    val card = if (assistant) palette.assistant else palette.user
    var editing by rememberSaveable(tone, assistant) { mutableStateOf<ColorPart?>(null) }
    val scheme = MaterialTheme.colorScheme

    BackHandler(onBack = onBack)
    Column(
        Modifier.fillMaxSize().background(scheme.background).safeDrawingPadding().imePadding()
            .padding(horizontal = 16.dp),
    ) {
        Header("消息样式", onBack) {
            TextButton(onClick = {
                store.update { MessageStyle() }
                editor.editAssistant(false)
                editing = null
            }) { Text("重置") }
        }
        Column(
            Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState())
                .padding(top = 8.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            SettingsCard {
                Column(Modifier.selectableGroup()) {
                    CardMaterial.entries.forEach { material ->
                        val selected = material == style.material
                        Row(
                            Modifier.fillMaxWidth().selectable(selected, role = Role.RadioButton) {
                                store.update { it.copy(material = material) }
                                editing = null
                            }.heightIn(min = 52.dp).padding(horizontal = 16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            MaterialSwatch(material)
                            Text(material.label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                            if (selected) Icon(Icons.Outlined.Check, null, Modifier.size(20.dp), tint = scheme.primary)
                        }
                    }
                }
            }
            SettingsCard {
                Row(
                    Modifier.fillMaxWidth().toggleable(value = style.assistantBubble, role = Role.Switch,
                        onValueChange = { checked -> store.update { it.copy(assistantBubble = checked) } })
                        .heightIn(min = 58.dp).padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("显示助手正文气泡", Modifier.weight(1f))
                    Switch(checked = style.assistantBubble, onCheckedChange = null)
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("配色预览", Modifier.padding(start = 4.dp),
                    style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                    verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    StyleTabs(
                        first = "浅色", second = "深色", secondSelected = tone == MessageTone.Dark,
                        onChange = { dark -> store.update { it.copy(tone = if (dark) MessageTone.Dark else MessageTone.Light) } },
                    )
                    StyleTabs(first = "用户", second = "助手", secondSelected = assistant, onChange = editor::editAssistant)
                }
            }
            MessageStylePreview(style, Modifier.fillMaxWidth())
            SettingsCard {
                Column {
                    if (!editable) Text("跟随主题配色", Modifier.padding(start = 16.dp, top = 14.dp, bottom = 2.dp),
                        style = MaterialTheme.typography.labelLarge, color = scheme.onSurfaceVariant)
                    ColorPart.entries.forEach { part ->
                        val active = editable && editing == part
                        ColorRow(part, part.read(card), expanded = active, onClick = if (editable) {
                            { editing = if (active) null else part }
                        } else null)
                        if (active) key(assistant, tone, part) {
                            val defaults = defaultMessagePalette(tone)
                            val initial = if (assistant) defaults.assistant else defaults.user
                            HexColorEditor(part, part.read(card), part.read(initial)) { hex ->
                                store.update { current ->
                                    val palette = current.palette
                                    val changed = if (assistant) palette.copy(assistant = part.write(palette.assistant, hex))
                                        else palette.copy(user = part.write(palette.user, hex))
                                    current.withPalette(current.tone, changed)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private val CardMaterial.label: String
    get() = when (this) {
        CardMaterial.Default -> "默认"
        CardMaterial.Frosted -> "磨砂玻璃"
        CardMaterial.Solid -> "纯色"
    }

@Composable
private fun MaterialSwatch(material: CardMaterial) {
    val scheme = MaterialTheme.colorScheme
    val (fill, stroke) = when (material) {
        CardMaterial.Default -> scheme.surfaceContainerHigh to scheme.outlineVariant
        CardMaterial.Frosted -> scheme.secondary.copy(alpha = .34f) to scheme.secondary.copy(alpha = .75f)
        CardMaterial.Solid -> scheme.primary to scheme.primary
    }
    Surface(
        Modifier.size(width = 30.dp, height = 24.dp), shape = RoundedCornerShape(7.dp),
        color = fill, border = BorderStroke(1.dp, stroke),
    ) { }
}

@Composable
private fun SettingsCard(content: @Composable () -> Unit) {
    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow, content = content)
}

@Composable
private fun StyleTabs(first: String, second: String, secondSelected: Boolean, onChange: (Boolean) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Row(
        Modifier.capsuleEdge().clip(CircleShape).background(scheme.surfaceContainerHigh).selectableGroup().padding(3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        listOf(first, second).forEachIndexed { index, name ->
            val active = (index == 1) == secondSelected
            Box(
                Modifier.then(if (active) Modifier.capsuleEdge() else Modifier)
                    .clip(CircleShape).background(if (active) scheme.onSurface else Color.Transparent)
                    .selectable(active, role = Role.Tab, onClick = { onChange(index == 1) })
                    .heightIn(min = 34.dp).padding(horizontal = 14.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(name, style = MaterialTheme.typography.labelLarge,
                    color = if (active) scheme.background else scheme.onSurfaceVariant)
            }
        }
    }
}
