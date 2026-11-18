package io.hydrion.hub.ui.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.hydrion.hub.ui.messages.MessageCardStyle
import io.hydrion.hub.ui.messages.messageColor
import io.hydrion.hub.ui.messages.normalizeMessageColor

internal enum class ColorPart(val label: String) {
    Background("背景色"), Border("边框色"), Ink("文字色");

    fun read(card: MessageCardStyle): String = when (this) {
        Background -> card.background
        Border -> card.border
        Ink -> card.text
    }

    fun write(card: MessageCardStyle, value: String): MessageCardStyle = when (this) {
        Background -> card.copy(background = value)
        Border -> card.copy(border = value)
        Ink -> card.copy(text = value)
    }
}

@Composable
internal fun ColorRow(part: ColorPart, hex: String, expanded: Boolean, onClick: (() -> Unit)?) {
    val colors = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().then(
            if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier
        ).heightIn(min = 52.dp).padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(Modifier.size(24.dp), color = hex.messageColor(), shape = CircleShape,
            border = BorderStroke(1.dp, colors.outline)) { }
        Text(part.label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Text(hex, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace,
            color = colors.onSurfaceVariant)
        if (onClick != null) Icon(if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null,
            Modifier.size(17.dp), tint = colors.onSurfaceVariant)
    }
}

@Composable
internal fun HexColorEditor(part: ColorPart, value: String, defaultValue: String, onChange: (String) -> Unit) {
    var input by rememberSaveable { mutableStateOf(value) }
    val normalized = normalizeMessageColor(input)
    val presets = remember(defaultValue) {
        listOf(defaultValue, "#FFFFFF", "#000000", "#242628", "#020562", "#D3F0FF").distinct()
    }
    Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp)) {
        OutlinedTextField(
            value = input,
            onValueChange = { raw ->
                input = raw
                normalizeMessageColor(raw)?.let { if (it != value) onChange(it) }
            },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text("${part.label}值") },
            textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
            isError = normalized == null && input.isNotEmpty(),
            supportingText = if (normalized == null) ({ Text("输入六位十六进制颜色，例如 #242628") }) else null,
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.Characters,
                keyboardType = KeyboardType.Ascii,
                imeAction = ImeAction.Done,
            ),
        )
        LazyRow(Modifier.fillMaxWidth().selectableGroup(), horizontalArrangement = Arrangement.spacedBy(1.dp)) {
            items(presets, key = { it }) { preset ->
                val color = preset.messageColor()
                val active = preset == value
                Box(
                    Modifier.size(42.dp).selectable(active, role = Role.RadioButton, onClick = {
                        input = preset
                        onChange(preset)
                    }).semantics { contentDescription = "使用 $preset" },
                    contentAlignment = Alignment.Center,
                ) {
                    Surface(Modifier.size(25.dp), color = color, shape = CircleShape,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)) {
                        if (active) Box(contentAlignment = Alignment.Center) {
                            Icon(Icons.Outlined.Check, null, Modifier.size(16.dp),
                                tint = if (color.luminance() > .4f) Color.Black else Color.White)
                        }
                    }
                }
            }
        }
    }
}
