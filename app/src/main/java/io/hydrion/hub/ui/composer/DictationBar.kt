package io.hydrion.hub.ui.composer

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import io.hydrion.hub.R
import io.hydrion.hub.ui.theme.HubStyle
import io.hydrion.hub.ui.theme.composerActionFill
import io.hydrion.hub.ui.theme.composerActionInk
import kotlinx.coroutines.delay

@Composable
internal fun DictationBar(
    level: () -> Float,
    onCancel: () -> Unit,
    onSave: () -> Unit,
    onSend: () -> Unit,
    canSend: Boolean,
    waiting: Boolean,
    resultReady: Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val cancelInk = if (colors.surface.luminance() < 0.5f) Color.White else colors.onSurface
    BoxWithConstraints(modifier.fillMaxWidth().testTag("chat-dictation-bar").padding(horizontal = 3.dp)) {
        val hitWidth = minOf(48.dp, (maxWidth - 18.dp).coerceAtLeast(0.dp) / 3)
        Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically) {
            DictationButton(hitWidth, "chat-dictation-cancel", stringResource(R.string.dictation_bar_cancel),
                enabled = true, onClick = onCancel) {
                Box(Modifier.size(HubStyle.ComposerActionDiameter).background(colors.outline, CircleShape),
                    contentAlignment = Alignment.Center) {
                    Canvas(Modifier.size(18.dp)) {
                        val delta = 5.5.dp.toPx()
                        val stroke = 2.dp.toPx()
                        drawLine(cancelInk, center - Offset(delta, delta), center + Offset(delta, delta), stroke, StrokeCap.Round)
                        drawLine(cancelInk, center - Offset(delta, -delta), center + Offset(delta, -delta), stroke, StrokeCap.Round)
                    }
                }
            }
            Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                if (waiting) DictationWaiting()
                else DictationWaveform(level, resultReady, Modifier.fillMaxWidth().height(24.dp))
            }
            DictationButton(hitWidth, "chat-dictation-save", stringResource(R.string.dictation_bar_save),
                enabled = !waiting, onClick = onSave) {
                Box(Modifier.size(HubStyle.ComposerActionDiameter)
                    .background(if (waiting) colors.surfaceContainerHighest else colors.outline, CircleShape),
                    contentAlignment = Alignment.Center) {
                    Box(Modifier.size(11.dp).background(
                        if (waiting) colors.onSurfaceVariant else Color.White, RoundedCornerShape(1.25.dp)))
                }
            }
            val sendEnabled = canSend && !waiting
            DictationButton(hitWidth, "chat-dictation-send", stringResource(R.string.dictation_bar_send),
                enabled = sendEnabled, onClick = onSend) {
                // Match the regular send circle's distance from the trailing edge of the same row.
                val margin = ((hitWidth - HubStyle.ComposerActionDiameter) / 2).coerceAtLeast(0.dp)
                val inset = ((hitWidth - ToolbarActionSlot) / 2 - SendFaceInset).coerceIn(-margin, margin)
                Box(Modifier.size(HubStyle.ComposerActionDiameter).offset(x = inset)
                    .background(colors.composerActionFill(sendEnabled), CircleShape),
                    contentAlignment = Alignment.Center) {
                    ComposerSendGlyph(colors.composerActionInk(sendEnabled))
                }
            }
        }
    }
}

@Composable
private fun DictationWaiting() {
    val label = stringResource(R.string.dictation_bar_finishing)
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    Row(Modifier.testTag("chat-dictation-waiting").clearAndSetSemantics {
        contentDescription = label
        liveRegion = LiveRegionMode.Polite
    },
        verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(Modifier.size(14.dp), color = color, strokeWidth = 1.5.dp)
        Text(label, Modifier.padding(start = 5.dp), style = MaterialTheme.typography.labelMedium,
            color = color, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun DictationButton(width: Dp, tag: String, label: String, enabled: Boolean, onClick: () -> Unit,
    content: @Composable () -> Unit) {
    val input = LocalInputModeManager.current
    Box(Modifier.width(width).fillMaxHeight().clip(CircleShape).testTag(tag)
        .focusProperties { canFocus = input.inputMode != InputMode.Touch }
        .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
        .semantics { contentDescription = label }, contentAlignment = Alignment.Center) {
        content()
    }
}

/** Only the Canvas observes the write cursor. Silence stops redraws after earlier samples scroll away. */
private class RmsWindow {
    val values = FloatArray(96)
    var next by mutableIntStateOf(0)
        private set
    private var nonzero = 0

    fun append(value: Float) {
        val sample = if (value.isFinite()) value.coerceIn(0f, 1f) else 0f
        if (sample == 0f && nonzero == 0) return
        if (values[next] > 0f) nonzero--
        if (sample > 0f) nonzero++
        values[next] = sample
        next = (next + 1) % values.size
    }
}

@Composable
private fun DictationWaveform(level: () -> Float, resultReady: Boolean, modifier: Modifier) {
    val window = remember(resultReady) { RmsWindow() }
    val currentLevel by rememberUpdatedState(level)
    LaunchedEffect(window) {
        if (resultReady) return@LaunchedEffect
        while (true) {
            window.append(currentLevel())
            delay(50L)
        }
    }
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    val label = stringResource(if (resultReady) R.string.dictation_bar_ready else R.string.dictation_bar_listening)
    Canvas(modifier.padding(horizontal = 3.dp).testTag("chat-dictation-waveform").semantics { contentDescription = label }) {
        val count = (size.width / 4.dp.toPx()).toInt().coerceAtMost(window.values.size)
        if (count == 0) return@Canvas
        val diameter = minOf(2.dp.toPx(), size.height)
        val spacing = size.width / count
        val next = window.next
        for (i in 0 until count) {
            val sample = window.values[(next + window.values.size - count + i) % window.values.size]
            val fromStart = spacing * (i + 0.5f)
            val x = if (layoutDirection == LayoutDirection.Ltr) fromStart else size.width - fromStart
            val extent = sample * (size.height - diameter) / 2
            if (extent == 0f) drawCircle(color, diameter / 2, Offset(x, center.y))
            else drawLine(color, Offset(x, center.y - extent), Offset(x, center.y + extent), diameter, StrokeCap.Round)
        }
    }
}
