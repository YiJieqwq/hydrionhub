package io.hydrion.hub.ui.sidebar

import android.icu.text.BreakIterator
import androidx.compose.foundation.combinedClickable
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.hydrion.hub.R
import io.hydrion.hub.model.LocalAssistant
import io.hydrion.hub.ui.theme.HubShapes
import io.hydrion.hub.ui.theme.IonMark

@Composable
internal fun AssistantAvatar(assistant: LocalAssistant, size: Dp = 32.dp) {
    val colors = MaterialTheme.colorScheme
    val initial = remember(assistant.name, assistant.avatarText) {
        if (assistant.avatarText.isNotBlank()) return@remember assistant.avatarText
        val chars = BreakIterator.getCharacterInstance()
        chars.setText(assistant.name)
        assistant.name.substring(0, chars.following(0))
    }
    Box(Modifier.size(size).clip(CircleShape).background(colors.primary.copy(alpha = 0.15f))
        .border(0.5.dp, colors.onSurface.copy(alpha = if (colors.surface.luminance() < 0.5f) 0.24f else 0.12f), CircleShape),
        contentAlignment = Alignment.Center) {
        if (assistant.id == LocalAssistant.Default.id && assistant.avatarText.isBlank()) IonMark(size)
        else Text(initial, maxLines = 1, fontSize = (size.value * 13.5f / 32f).sp, fontWeight = FontWeight.Medium, color = colors.primary,
            modifier = Modifier.clearAndSetSemantics { })
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
internal fun AssistantRow(assistant: LocalAssistant, selected: Boolean, enabled: Boolean, showSelection: Boolean = false,
    loading: Boolean = false, onMenu: (() -> Unit)? = null, onSelect: () -> Unit, onEdit: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().clip(HubShapes.Small)
        .then(if (onMenu != null) Modifier.background(colors.surface) else Modifier)
        .testTag("assistant-row-${assistant.id}")
        .then(if (onMenu == null) Modifier.clickable(enabled = enabled, role = Role.Button, onClick = onSelect)
            else Modifier.combinedClickable(enabled = enabled, role = Role.RadioButton, onClick = onSelect, onLongClick = onMenu)
                .semantics { this.selected = selected })
        .heightIn(min = 48.dp).padding(start = 4.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        AssistantAvatar(assistant)
        Text(assistant.name, Modifier.padding(start = 16.dp).weight(1f), fontSize = 15.sp,
            fontWeight = if (onMenu != null) FontWeight.Medium else FontWeight.Normal, color = colors.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (loading) DrawerLoadingDot()
        if (showSelection && selected) Icon(Icons.Outlined.Check, null, Modifier.size(18.dp), tint = colors.primary)
        Spacer(Modifier.width(8.dp))
        IconButton(onEdit, enabled = enabled) {
            Icon(Icons.Outlined.Edit, stringResource(R.string.assistant_edit, assistant.name), Modifier.size(18.dp),
                tint = colors.onSurface.copy(alpha = 0.7f))
        }
    }
}

@Composable
internal fun AssistantNameDialog(assistant: LocalAssistant?, onClose: () -> Unit, onSave: (String) -> Boolean) {
    var name by rememberSaveable(assistant?.id) { mutableStateOf(assistant?.name.orEmpty()) }
    AlertDialog(onDismissRequest = onClose,
        title = { Text(stringResource(if (assistant == null) R.string.assistant_add else R.string.assistant_rename)) },
        text = {
            OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true,
                label = { Text(stringResource(R.string.assistant_name)) })
        },
        confirmButton = { TextButton(enabled = LocalAssistant.validName(name.trim()), onClick = {
            if (onSave(name)) onClose()
        }) { Text(stringResource(R.string.settings_save)) } },
        dismissButton = { TextButton(onClick = onClose) { Text(stringResource(R.string.settings_cancel)) } })
}
