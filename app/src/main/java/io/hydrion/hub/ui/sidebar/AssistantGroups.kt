package io.hydrion.hub.ui.sidebar

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.hydrion.hub.R
import io.hydrion.hub.model.LocalAssistant

@Composable
internal fun AssistantGroups(assistants: List<LocalAssistant>, row: @Composable (LocalAssistant) -> Unit) {
    val groups = remember(assistants) { assistants.groupBy { it.sidebarGroup } }
    var collapsed by rememberSaveable { mutableStateOf(arrayListOf<String>()) }
    Column(Modifier.padding(start = 10.dp, end = 10.dp, top = 6.dp, bottom = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        groups[null].orEmpty().forEach { key(it.id) { row(it) } }
        groups.forEach { (name, members) ->
            if (name != null) key(name) {
                val open = name !in collapsed
                val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
                val angle by animateFloatAsState(if (open) (if (rtl) -90f else 90f) else 0f, tween(260), label = "Group")
                Row(Modifier.fillMaxWidth().clickable { collapsed = ArrayList(if (open) collapsed + name else collapsed - name) }
                    .heightIn(min = 40.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                        stringResource(if (open) R.string.drawer_expanded else R.string.drawer_collapsed),
                        Modifier.size(16.dp).rotate(angle))
                    Text(name, Modifier.padding(start = 8.dp), fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                AnimatedVisibility(open, enter = expandVertically(tween(260)), exit = shrinkVertically(tween(260))) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        members.forEach { key(it.id) { row(it) } }
                    }
                }
            }
        }
    }
}

@Composable
internal fun AssistantGroupDialog(item: LocalAssistant, groups: List<String>, close: () -> Unit, save: (String) -> Unit) {
    var name by rememberSaveable(item.id) { mutableStateOf(item.sidebarGroup.orEmpty()) }
    AlertDialog(onDismissRequest = close, title = { Text(stringResource(R.string.drawer_group)) },
        text = { Column(Modifier.verticalScroll(rememberScrollState())) {
            OutlinedTextField(name, { name = it }, singleLine = true, label = { Text(stringResource(R.string.drawer_group_name)) })
            TextButton({ save("") }) { Text(stringResource(R.string.drawer_ungrouped)) }
            groups.forEach { group -> TextButton({ save(group) }) { Text(group) } }
        } },
        confirmButton = { TextButton({ save(name.trim()) }, enabled = LocalAssistant.validName(name.trim())) {
            Text(stringResource(R.string.settings_save))
        } }, dismissButton = { TextButton(close) { Text(stringResource(R.string.settings_cancel)) } })
}
