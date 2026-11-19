package io.hydrion.hub.provider

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

@Composable
internal fun ModelGroup(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
        if (title.isNotBlank()) Text(title, style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.Medium)
        Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
            Column(Modifier.padding(horizontal = 13.dp, vertical = 7.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp), content = content)
        }
    }
}

@Composable
internal fun ModelField(
    label: String, value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier,
    singleLine: Boolean = true, keyboard: KeyboardType = KeyboardType.Text, placeholder: String? = null,
    trailingIcon: (@Composable () -> Unit)? = null
) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        TextField(value, onChange, Modifier.fillMaxWidth().semantics { contentDescription = label },
            singleLine = singleLine, maxLines = if (singleLine) 1 else 8, trailingIcon = trailingIcon,
            placeholder = placeholder?.let { hint -> { Text(hint) } },
            keyboardOptions = KeyboardOptions(keyboardType = keyboard), shape = RoundedCornerShape(12.dp),
            colors = TextFieldDefaults.colors(focusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent))
    }
}

@Composable
internal fun ModelToggle(label: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onChange)
        .heightIn(min = 43.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}

@Composable
internal fun ModelChoice(label: String, value: String, options: List<Pair<String, String>>, onChange: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        Row(Modifier.fillMaxWidth().clickable { expanded = true }.heightIn(min = 44.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Text(options.find { it.first == value }?.second ?: value, modifier = Modifier.weight(1.3f, fill = false),
                style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Icon(Icons.Outlined.ExpandMore, null, Modifier.size(18.dp))
        }
        DropdownMenu(expanded, { expanded = false }) {
            options.forEach { (key, text) ->
                DropdownMenuItem(text = { Text(text) },
                    trailingIcon = if (value == key) ({ Icon(Icons.Outlined.Check, null, Modifier.size(17.dp)) }) else null,
                    onClick = { expanded = false; onChange(key) })
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ModelChips(options: List<Pair<String, String>>, selected: Set<String>, onClick: (String) -> Unit) {
    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
        options.forEach { (id, label) ->
            FilterChip(selected = id in selected, onClick = { onClick(id) }, label = { Text(label) },
                shape = RoundedCornerShape(12.dp), colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                    selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer))
        }
    }
}

internal val profileIcons = listOf("chat" to "对话", "image" to "图片", "bolt" to "闪电", "code" to "代码",
    "cube" to "立方体", "sparkle" to "星光")

@Composable
internal fun ProfileIcon(key: String, type: ModelType, modifier: Modifier = Modifier) {
    val icon = when (key) {
        "chat" -> Icons.Outlined.ChatBubbleOutline
        "image" -> Icons.Outlined.Image
        "bolt" -> Icons.Outlined.Bolt
        "code" -> Icons.Outlined.Code
        "cube" -> Icons.Outlined.ViewInAr
        "sparkle" -> Icons.Outlined.AutoAwesome
        else -> when (type) {
            ModelType.Chat -> Icons.Outlined.ChatBubbleOutline
            ModelType.Image -> Icons.Outlined.Image
            ModelType.Embedding -> Icons.Outlined.ViewInAr
        }
    }
    Icon(icon, null, modifier)
}
