package io.hydrion.hub.ui.sidebar

import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.automirrored.outlined.DriveFileMove
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.hydrion.hub.R
import io.hydrion.hub.model.Conversation
import io.hydrion.hub.model.isActive
import io.hydrion.hub.ui.theme.HubShapes

internal class DrawerActions(
    val hasDraft: (String) -> Boolean,
    val pin: (Set<String>, Boolean) -> Unit,
    val move: (Set<String>, String) -> Unit,
    val duplicate: (String, String) -> Unit,
    val delete: (Set<String>) -> Unit,
    val copyAssistant: (String, String) -> Boolean,
    val groupAssistant: (String, String) -> Boolean,
    val canRemoveAssistant: (String) -> Boolean,
    val removeAssistant: (String) -> Unit,
    val setArchived: (String, Boolean) -> Unit = { _, _ -> },
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DrawerActionSheet(title: String?, close: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    ModalBottomSheet(onDismissRequest = close, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = HubShapes.Sheet,
        dragHandle = { Spacer(Modifier.padding(top = 12.dp, bottom = 10.dp).size(40.dp, 4.dp)
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = .20f), RoundedCornerShape(2.dp))) }) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, bottom = 16.dp)) {
            if (title != null) Text(title, Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            content()
        }
    }
}

@Composable
internal fun DrawerAction(label: String, icon: ImageVector, enabled: Boolean = true, destructive: Boolean = false, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val tint = (if (destructive) colors.error else colors.onSurface).copy(alpha = if (enabled) 1f else .38f)
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(HubShapes.Small).background(colors.surfaceContainerHigh)
        .clickable(enabled = enabled, onClick = onClick).heightIn(min = 48.dp).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Icon(icon, null, Modifier.size(20.dp), tint = tint)
        Text(label, color = tint, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
internal fun ChatActionsSheet(item: Conversation, movable: Boolean, close: () -> Unit,
    select: () -> Unit, rename: () -> Unit, pin: () -> Unit, duplicate: () -> Unit, move: () -> Unit, archive: () -> Unit, delete: () -> Unit) {
    val idle = item.messages.none { it.status.isActive }
    DrawerActionSheet(null, close) {
        DrawerAction(stringResource(R.string.drawer_select), Icons.Outlined.Checklist, onClick = select)
        DrawerAction(stringResource(R.string.drawer_rename), Icons.Outlined.Edit, onClick = rename)
        DrawerAction(stringResource(if (item.pinned) R.string.drawer_unpin else R.string.drawer_pin), Icons.Outlined.PushPin, onClick = pin)
        DrawerAction(stringResource(R.string.drawer_duplicate), Icons.Outlined.ContentCopy, enabled = idle, onClick = duplicate)
        DrawerAction(stringResource(R.string.drawer_move), Icons.AutoMirrored.Outlined.DriveFileMove, enabled = movable && idle, onClick = move)
        DrawerAction(stringResource(R.string.history_archive), Icons.Outlined.Archive, enabled = idle, onClick = archive)
        DrawerAction(stringResource(R.string.drawer_delete), Icons.Outlined.DeleteOutline, destructive = true, onClick = delete)
    }
}

@Composable
internal fun SelectionHeader(count: Int, all: Boolean, cancel: () -> Unit, toggleAll: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(cancel) { Icon(Icons.Outlined.Close, stringResource(R.string.settings_cancel)) }
        Text(stringResource(R.string.drawer_selected_count, count), Modifier.weight(1f),
            textAlign = TextAlign.Center, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Row(Modifier.clip(HubShapes.Small).clickable(onClick = toggleAll).heightIn(min = 48.dp)
            .padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Checkbox(all, onCheckedChange = null, modifier = Modifier.size(18.dp))
            Text(stringResource(if (all) R.string.drawer_clear_selection else R.string.drawer_select_all),
                Modifier.padding(start = 3.dp), fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
internal fun SelectionFooter(allPinned: Boolean, movable: Boolean, enabled: Boolean, pin: () -> Unit, move: () -> Unit, delete: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().background(colors.surfaceContainerLow, HubShapes.Sheet)
        .padding(8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        BulkButton(stringResource(if (allPinned) R.string.drawer_unpin else R.string.drawer_pin),
            Icons.Outlined.PushPin, colors.onSurface, enabled, pin)
        BulkButton(stringResource(R.string.drawer_move_short), Icons.AutoMirrored.Outlined.DriveFileMove, colors.primary, movable, move)
        BulkButton(stringResource(R.string.drawer_delete), Icons.Outlined.DeleteOutline, colors.error, enabled, delete)
    }
}

@Composable
private fun RowScope.BulkButton(label: String, icon: ImageVector, tint: Color, enabled: Boolean, action: () -> Unit) {
    val dark = MaterialTheme.colorScheme.surface.luminance() < .5f
    Row(Modifier.weight(1f).clip(HubShapes.Small).background(tint.copy(alpha = if (dark) .18f else .14f))
        .clickable(enabled = enabled, onClick = action).heightIn(min = 48.dp).padding(horizontal = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically) {
        val foreground = tint.copy(alpha = if (enabled) 1f else .4f)
        Icon(icon, null, Modifier.size(16.dp), tint = foreground)
        Text(label, color = foreground, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
internal fun ConfirmDrawerDelete(text: String, cancel: () -> Unit, confirm: () -> Unit) {
    AlertDialog(onDismissRequest = cancel, title = { Text(stringResource(R.string.drawer_delete)) },
        text = { Text(text) }, confirmButton = {
            TextButton(confirm) { Text(stringResource(R.string.drawer_delete), color = MaterialTheme.colorScheme.error) }
        }, dismissButton = { TextButton(cancel) { Text(stringResource(R.string.settings_cancel)) } })
}
