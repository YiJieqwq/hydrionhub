package io.hydrion.hub.ui.composer

import androidx.annotation.DrawableRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.hydrion.hub.R

@Composable
internal fun Modifier.keepToolEditorFocus(): Modifier {
    val input = LocalInputModeManager.current
    return focusProperties { canFocus = input.inputMode != InputMode.Touch }
}

@Composable
internal fun PanelToolRow(
    title: String, @DrawableRes icon: Int, detail: String? = null, enabled: Boolean, id: String,
    value: String? = null, checked: Boolean? = null, available: Boolean = enabled, highlight: Boolean = checked == true,
    chevron: Boolean = true, compactActions: Boolean = false, controls: (@Composable () -> Unit)? = null, onClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val ink = when {
        highlight -> colors.primary
        available -> colors.onSurface
        else -> colors.onSurface.copy(alpha = .5f)
    }
    val action = if (checked == null) Modifier.clickable(enabled = enabled, role = Role.Button, onClick = onClick)
        else Modifier.toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = { onClick() })
    PanelStep(Modifier.fillMaxWidth(), inheritGroupPull = true) {
        Row(Modifier.fillMaxWidth().padding(end = if (compactActions) 6.dp else 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f).clip(MaterialTheme.shapes.small).testTag("panel-action:$id").keepToolEditorFocus().then(action)
                .heightIn(min = if (detail.isNullOrEmpty()) 48.dp else 56.dp).padding(start = 12.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Icon(painterResource(icon), null, Modifier.size(20.dp), tint = ink)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.bodyMedium, fontSize = 15.sp, letterSpacing = 0.sp, color = ink,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (!detail.isNullOrEmpty()) {
                        Text(detail, style = MaterialTheme.typography.bodySmall, fontSize = 13.sp, lineHeight = 17.sp,
                            maxLines = 2, overflow = TextOverflow.Ellipsis, color = colors.onSurfaceVariant)
                    }
                }
                if (!value.isNullOrEmpty()) {
                    Spacer(Modifier.width(6.dp))
                    Text(value, Modifier.widthIn(max = 128.dp), style = MaterialTheme.typography.bodySmall, fontSize = 13.sp,
                        textAlign = TextAlign.End, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (checked != null) {
                    Spacer(Modifier.width(8.dp))
                    PanelSwitchTrack(checked, enabled, id)
                } else if (enabled && chevron) {
                    Spacer(Modifier.width(6.dp))
                    Icon(painterResource(R.drawable.lucide_chevron_right), null, Modifier.size(18.dp), tint = colors.onSurfaceVariant)
                }
            }
            controls?.invoke()
        }
    }
}

@Composable
internal fun InlinePanelAction(title: String, @DrawableRes icon: Int, enabled: Boolean, id: String, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Box(Modifier.size(width = 30.dp, height = 48.dp).testTag("panel-action:$id").keepToolEditorFocus()
        .clickable(enabled = enabled, role = Role.Button, onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(painterResource(icon), title, Modifier.size(18.dp), tint = colors.onSurface.copy(alpha = if (enabled) .7f else .38f))
    }
}

@Composable
internal fun ManualPanelSwitch(checked: Boolean, enabled: Boolean, label: String, id: String, onToggle: (Boolean) -> Unit) {
    Box(Modifier.size(48.dp).testTag("panel-switch:$id").keepToolEditorFocus()
        .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onToggle)
        .semantics { contentDescription = label }, contentAlignment = Alignment.CenterEnd) {
        PanelSwitchTrack(checked, enabled, id)
    }
}

@Composable
private fun PanelSwitchTrack(checked: Boolean, enabled: Boolean, id: String) {
    val colors = MaterialTheme.colorScheme
    Box(Modifier.size(width = 44.dp, height = 26.dp).testTag("panel-track:$id"), contentAlignment = Alignment.Center) {
        // Keep Material's state animation and shrink only the painted switch, independently of the hit target.
        Switch(checked, onCheckedChange = null, enabled = enabled, thumbContent = {},
            modifier = Modifier.requiredSize(52.dp, 32.dp).graphicsLayer { scaleX = 44f / 52f; scaleY = 26f / 32f },
            colors = SwitchDefaults.colors(checkedTrackColor = colors.primary, checkedThumbColor = colors.onPrimary,
                uncheckedTrackColor = colors.surfaceContainerLowest, uncheckedThumbColor = colors.outline,
                uncheckedBorderColor = colors.outlineVariant, disabledUncheckedBorderColor = colors.outlineVariant.copy(alpha = .5f)))
    }
}
