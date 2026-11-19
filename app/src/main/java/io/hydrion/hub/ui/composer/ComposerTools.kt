package io.hydrion.hub.ui.composer

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AddPhotoAlternate
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.automirrored.outlined.ManageSearch
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.hydrion.hub.model.Draft
import io.hydrion.hub.model.Mode

private const val Disabled = "禁用"
private data class ToolChoice(val label: String, val icon: ImageVector)

private val searchChoices = listOf(
    ToolChoice("模型内置搜索", Icons.Outlined.Language),
    ToolChoice("自定义搜索", Icons.AutoMirrored.Outlined.ManageSearch),
    ToolChoice(Disabled, Icons.Outlined.Block),
)
private val imageChoices = listOf(
    ToolChoice("自定义图片生成", Icons.Outlined.Palette),
    ToolChoice("模型内置图片生成", Icons.Outlined.AutoAwesome),
    ToolChoice(Disabled, Icons.Outlined.Block),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ComposerTools(
    mode: Mode,
    draft: Draft,
    modelLabel: String,
    onSearch: (String) -> Unit,
    onImageTool: (String) -> Unit,
    onPickReference: () -> Unit,
    onConfigure: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = colors.surfaceContainer,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
    ) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, bottom = 20.dp),
        ) {
            Box(Modifier.fillMaxWidth().padding(bottom = 12.dp), contentAlignment = Alignment.Center) {
                Text("Tools", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                if (mode == Mode.Chat) {
                    val enabled = listOf(draft.search, draft.imageTool).count { it != Disabled }
                    Text(
                        "$enabled / 2",
                        Modifier.align(Alignment.CenterEnd).padding(end = 8.dp)
                            .clearAndSetSemantics { contentDescription = "已启用 $enabled 个工具，共 2 个" },
                        style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant,
                    )
                }
            }
            if (mode == Mode.Chat) {
                ToolGroup("网络搜索", searchChoices, draft.search, initiallyOpen = true, onSelect = onSearch)
                HorizontalDivider(Modifier.padding(horizontal = 8.dp), color = colors.outlineVariant)
                ToolGroup("图片生成", imageChoices, draft.imageTool, initiallyOpen = false, onSelect = onImageTool)
            } else {
                val references = draft.attachments.count { it.imageKey != null || it.mimeType?.startsWith("image/") == true }
                ImageAction(Icons.Outlined.AddPhotoAlternate, "添加参考图", "已选 $references 张", onPickReference)
                HorizontalDivider(Modifier.padding(horizontal = 8.dp), color = colors.outlineVariant)
                ImageAction(Icons.Outlined.Tune, "生成参数", modelLabel, onConfigure)
            }
        }
    }
}

@Composable
private fun ToolGroup(
    title: String,
    choices: List<ToolChoice>,
    selected: String,
    initiallyOpen: Boolean,
    onSelect: (String) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    var expanded by rememberSaveable(title) { mutableStateOf(initiallyOpen) }
    Row(
        Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small)
            .clickable(role = Role.Button, onClickLabel = if (expanded) "收起" else "展开") { expanded = !expanded }
            .semantics { stateDescription = if (expanded) "已展开" else "已收起" }
            .heightIn(min = 52.dp).padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(if (expanded) Icons.Outlined.ExpandMore else Icons.Outlined.ChevronRight, null,
            Modifier.size(20.dp), tint = colors.onSurfaceVariant)
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        Text(selected, Modifier.weight(1.25f), style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.End, color = colors.onSurfaceVariant)
    }
    AnimatedVisibility(expanded) {
        Column(Modifier.fillMaxWidth().selectableGroup().padding(bottom = 6.dp)) {
            choices.forEach { choice ->
                val checked = selected == choice.label
                Row(
                    Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small)
                        .selectable(selected = checked, role = Role.RadioButton, onClick = { onSelect(choice.label) })
                        .heightIn(min = 46.dp).padding(start = 20.dp, end = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Icon(choice.icon, null, Modifier.size(20.dp),
                        tint = if (choice.label == Disabled) colors.onSurface.copy(alpha = .38f) else colors.onSurfaceVariant)
                    Text(choice.label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    RadioButton(selected = checked, onClick = null,
                        colors = RadioButtonDefaults.colors(selectedColor = colors.onSurface, unselectedColor = colors.outline))
                }
            }
        }
    }
}

@Composable
private fun ImageAction(icon: ImageVector, title: String, value: String, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small).clickable(role = Role.Button, onClick = onClick)
            .heightIn(min = 54.dp).padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, Modifier.size(21.dp), tint = colors.onSurfaceVariant)
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(value, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Icon(Icons.Outlined.ChevronRight, null, Modifier.size(19.dp), tint = colors.onSurfaceVariant)
    }
}
