package io.hydrion.hub.ui.composer

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.ChevronRight
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.hydrion.hub.R
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import io.hydrion.hub.ui.messages.LocalMessageStyle
import io.hydrion.hub.ui.messages.MessageTone
import io.hydrion.hub.ui.messages.timelineScheme
import io.hydrion.hub.model.CUSTOM_IMAGE_TOOL
import io.hydrion.hub.model.Draft
import io.hydrion.hub.model.HubViewModel
import kotlinx.coroutines.delay

// Some keyboards never acknowledge a hide request. Keep the tools reachable in that case.
private const val ToolsImeFallbackMillis = 1_000L

@Composable
internal fun AwaitToolsImeDismissal(imeActive: Boolean, eligible: Boolean, onFinish: (present: Boolean) -> Unit) {
    val finish by rememberUpdatedState(onFinish)
    val view = LocalView.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) { finish(false) }
    LaunchedEffect(imeActive, eligible) {
        if (eligible && imeActive) delay(ToolsImeFallbackMillis)
        finish(eligible && view.hasWindowFocus() && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
    }
}

private const val Disabled = "禁用"
private data class ToolChoice(val value: String, val label: String, val icon: () -> ImageVector)

@Composable
internal fun toolPanelScheme(): androidx.compose.material3.ColorScheme {
    val tone = LocalMessageStyle.current.tone
    val palette = timelineScheme(tone)
    return remember(tone, palette) {
        if (tone == MessageTone.Light) palette else palette.copy(surface = Color(0xFF1D1D1D),
            primary = Color(0xFFB8C2FF), onPrimary = Color(0xFF232A42),
            surfaceContainerLowest = Color(0xFF111111), onSurfaceVariant = Color(0xFFADADAD))
    }
}

@Composable
internal fun ChatToolsPage(
    search: String, imageTool: String, onSearch: (String) -> Unit, onImageTool: (String) -> Unit,
    vm: HubViewModel, modelToolsEnabled: Boolean, modifier: Modifier = Modifier, onBack: () -> Unit,
) {
    val browsing = rememberScrollState()
    val count = if (modelToolsEnabled) listOf(search, imageTool).count { it != Disabled } else 0
    val countLabel = stringResource(R.string.chat_tools_enabled_count, count)
    val scheme = toolPanelScheme()
    MaterialTheme(colorScheme = scheme, typography = MaterialTheme.typography, shapes = MaterialTheme.shapes) {
        CompositionLocalProvider(LocalContentColor provides scheme.onSurface) {
            Column(modifier.fillMaxWidth()) {
                ToolPanelHeading(onBack, countLabel)
                Spacer(Modifier.height(4.dp))
                PanelViewport(Modifier.weight(1f, fill = false).fillMaxWidth().padding(horizontal = 16.dp)) {
                    Column(Modifier.fillMaxWidth().testTag("chat-tools-page").verticalScroll(browsing).padding(bottom = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        ChatToolGroups(search, imageTool, "", onSearch, onImageTool, vm, modelToolsEnabled)
                    }
                }
            }
        }
    }
}

@Composable
private fun ChatToolGroups(
    search: String, imageTool: String, query: String, onSearch: (String) -> Unit, onImageTool: (String) -> Unit, vm: HubViewModel,
    modelToolsEnabled: Boolean,
) {
    val disabled = ToolChoice(Disabled, stringResource(R.string.chat_tools_disabled)) { Icons.Outlined.Block }
    val web = listOf(
        ToolChoice("模型内置搜索", stringResource(R.string.chat_tools_builtin_search)) { Icons.Outlined.Language },
        ToolChoice("自定义搜索", stringResource(R.string.chat_tools_custom_search)) { Icons.AutoMirrored.Outlined.ManageSearch },
        disabled,
    )
    val image = listOf(ToolChoice(CUSTOM_IMAGE_TOOL, stringResource(R.string.chat_tools_custom_image)) { Icons.Outlined.Palette }, disabled)
    val webTitle = stringResource(R.string.chat_tools_web)
    val imageTitle = stringResource(R.string.chat_tools_image)
    val webMatches = matchingToolChoices(query, webTitle, web) { it.label }
    val imageMatches = matchingToolChoices(query, imageTitle, image) { it.label }
    HostToolGroups(vm, query, webMatches.isNotEmpty() || imageMatches.isNotEmpty())
    ToolGroup("web", webTitle, web, webMatches, search, initiallyOpen = true,
        filtering = query.isNotEmpty(), enabled = modelToolsEnabled, onSelect = onSearch)
    ToolGroup("image", imageTitle, image, imageMatches, imageTool, initiallyOpen = false,
        filtering = query.isNotEmpty(), enabled = modelToolsEnabled, onSelect = onImageTool)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ImageComposerTools(
    draft: Draft,
    modelLabel: String,
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
            }
            val references = draft.attachments.count { it.imageKey != null || it.mimeType?.startsWith("image/") == true }
            ImageAction(Icons.Outlined.AddPhotoAlternate, "添加参考图", "已选 $references 张", onPickReference)
            HorizontalDivider(Modifier.padding(horizontal = 8.dp), color = colors.outlineVariant)
            ImageAction(Icons.Outlined.Tune, "生成参数", modelLabel, onConfigure)
        }
    }
}

@Composable
private fun ToolGroup(
    id: String, title: String, choices: List<ToolChoice>, matches: List<ToolChoice>, selected: String,
    initiallyOpen: Boolean, filtering: Boolean, enabled: Boolean, onSelect: (String) -> Unit,
) {
    val inputMode = LocalInputModeManager.current
    val keepEditorFocus = Modifier.focusProperties { canFocus = inputMode.inputMode != InputMode.Touch }
    val detail = if (enabled) choices.find { it.value == selected }?.label ?: selected else stringResource(R.string.panel_model_unavailable)
    PanelSection(id, title, detail, matches.isNotEmpty(), initiallyOpen, filtering) {
        val colors = MaterialTheme.colorScheme
        Column(Modifier.fillMaxWidth().selectableGroup(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            matches.forEach { choice ->
                val checked = selected == choice.value
                PanelStep(Modifier.fillMaxWidth(), inheritGroupPull = true) {
                    Row(
                        Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small).then(keepEditorFocus)
                            .testTag("chat-tools-option:$id:${choice.value}")
                            .selectable(selected = checked, enabled = enabled, role = Role.RadioButton, onClick = { onSelect(choice.value) })
                            .heightIn(min = 48.dp).padding(horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Icon(choice.icon(), null, Modifier.size(20.dp),
                            tint = if (choice.value == Disabled) colors.onSurface.copy(alpha = .38f) else colors.onSurfaceVariant)
                        Text(choice.label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, fontSize = 15.sp,
                            color = if (!enabled) colors.onSurface.copy(alpha = .5f) else if (checked && choice.value != Disabled) colors.primary else colors.onSurface)
                        RadioButton(selected = checked, onClick = null, enabled = enabled,
                            colors = RadioButtonDefaults.colors(selectedColor = colors.primary, unselectedColor = colors.outline))
                    }
                }
            }
        }
    }
}

@Composable
internal fun PanelSection(id: String, title: String, summary: String, visible: Boolean, initiallyOpen: Boolean,
    filtering: Boolean, summaryOnCollapseOnly: Boolean = false, summaryDescription: String? = null,
    content: @Composable () -> Unit) {
    var expanded by rememberSaveable(id) { mutableStateOf(initiallyOpen) }
    if (!visible) return
    val showChoices = filtering || expanded
    val describeState = stringResource(if (showChoices) R.string.chat_tools_expanded else R.string.chat_tools_collapsed)
    val toggleLabel = stringResource(if (expanded) R.string.chat_tools_collapse else R.string.chat_tools_expand)
    val inputMode = LocalInputModeManager.current
    val keepEditorFocus = Modifier.focusProperties { canFocus = inputMode.inputMode != InputMode.Touch }
    val toggle = if (filtering) Modifier else keepEditorFocus.clickable(role = Role.Button, onClickLabel = toggleLabel) { expanded = !expanded }
    PanelStep(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().testTag("chat-tools-section:$id")) {
            val colors = MaterialTheme.colorScheme
            Row(
                Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small).testTag("chat-tools-group:$id").then(toggle)
                    .semantics { stateDescription = describeState }
                    .heightIn(min = 32.dp).padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Box(Modifier.size(20.dp), contentAlignment = Alignment.Center) {
                    Icon(painterResource(if (showChoices) R.drawable.lucide_chevron_down else R.drawable.lucide_chevron_right),
                        null, Modifier.size(14.dp), tint = colors.onSurfaceVariant)
                }
                Text(title, Modifier.weight(1f), style = MaterialTheme.typography.labelLarge,
                    fontSize = 13.sp, fontWeight = FontWeight.Medium, color = colors.onSurfaceVariant)
                if (summary.isNotEmpty() && (!summaryOnCollapseOnly || !showChoices)) {
                    val description = if (summaryDescription == null) Modifier else Modifier.semantics { contentDescription = summaryDescription }
                    Text(summary, Modifier.widthIn(max = 160.dp).then(description),
                        style = MaterialTheme.typography.bodySmall, fontSize = 13.sp, maxLines = 1,
                        overflow = TextOverflow.Ellipsis, textAlign = TextAlign.End, color = colors.onSurfaceVariant)
                }
            }
            AnimatedVisibility(showChoices) { content() }
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
