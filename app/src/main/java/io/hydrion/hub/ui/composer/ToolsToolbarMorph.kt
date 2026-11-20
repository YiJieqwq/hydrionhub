package io.hydrion.hub.ui.composer

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import io.hydrion.hub.R
import io.hydrion.hub.ui.composerActionFace
import io.hydrion.hub.ui.theme.HubStyle
import io.hydrion.hub.ui.theme.composerActionFill
import io.hydrion.hub.ui.theme.composerActionInk
import kotlin.math.roundToInt

@Composable
internal fun ToolsToolbarMorph(
    progress: Float,
    toolsOpen: Boolean,
    hasClearableTools: Boolean,
    generating: Boolean,
    canSend: Boolean,
    onAttach: () -> Unit,
    onSend: () -> Unit,
    onClear: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    middle: @Composable RowScope.(leading: Modifier, trailing: Modifier) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val density = LocalDensity.current
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    BoxWithConstraints(modifier) {
        val pose = ToolsToolbarPose(constraints.maxWidth.toFloat(), with(density) { 42.dp.roundToPx().toFloat() }, progress, rtl)
        val p = pose.fraction
        val actions = pose.actions(toolsOpen)
        val ordinary = actions == ToolsToolbarActions.Composer
        val tools = actions == ToolsToolbarActions.Tools
        val slot = with(density) { pose.slotWidth.toDp() }
        if (p < 1f) {
            fun motion(leading: Boolean) = Modifier.graphicsLayer {
                translationX = pose.moveToCenter(size.width, leading)
                alpha = pose.middleAlpha
            }
            val semantics = if (ordinary) Modifier else Modifier.clearAndSetSemantics {}
            Row(Modifier.align(Alignment.Center).fillMaxWidth().padding(horizontal = slot).then(semantics),
                verticalAlignment = Alignment.CenterVertically) {
                middle(motion(true), motion(false))
            }
        }
        if (actions == ToolsToolbarActions.Moving) {
            // Below the two endpoints and above the old controls. A gesture begun mid-flight
            // cannot fall through to a fading control or complete on a newly arrived action.
            // Once parked, leave the search field between the two buttons directly tappable.
            Spacer(Modifier.matchParentSize().pointerInput(Unit) {
                awaitPointerEventScope { while (true) awaitPointerEvent().changes.forEach { it.consume() } }
            })
        }
        val clear = stringResource(R.string.chat_tools_clear)
        val back = stringResource(R.string.chat_tools_back)
        val newInk = colors.onSurface
        val endpoint = Modifier.align(AbsoluteAlignment.CenterLeft)
        Box(endpoint.absoluteOffset { IntOffset(pose.attachmentX.roundToInt(), 0) }
            .width(slot).height(48.dp).testTag("chat-toolbar-attach-glyph-slot"), contentAlignment = Alignment.Center) {
            ToolbarAction(
                tag = if (tools) "chat-tools-clear" else "chat-composer-attach",
                label = if (tools) clear else "添加附件", interactive = actions != ToolsToolbarActions.Moving, keepEditorFocus = tools,
                enabled = ordinary || hasClearableTools, onClick = if (tools) onClear else onAttach,
            ) {
                val ink = if (p == 0f) Color.White else lerp(Color.White, if (hasClearableTools) newInk else colors.onSurfaceVariant, p)
                Canvas(Modifier.size(18.dp).graphicsLayer { rotationZ = pose.plusDegrees }) {
                    val stroke = 2.dp.toPx()
                    val r = size.minDimension / 2 - stroke / 2
                    drawLine(ink, center - Offset(r, 0f), center + Offset(r, 0f), stroke, StrokeCap.Round)
                    drawLine(ink, center - Offset(0f, r), center + Offset(0f, r), stroke, StrokeCap.Round)
                }
            }
        }
        Box(endpoint.absoluteOffset { IntOffset(pose.sendX.roundToInt(), 0) }
            .width(slot).height(48.dp).testTag("chat-toolbar-send-glyph-slot"), contentAlignment = Alignment.Center) {
            ToolbarAction(
                tag = if (tools) "chat-tools-back" else "chat-composer-send",
                label = if (tools) back else if (generating) "停止生成" else "发送消息",
                interactive = actions != ToolsToolbarActions.Moving, keepEditorFocus = tools, enabled = tools || generating || canSend,
                onClick = if (tools) onBack else onSend,
            ) {
                val fill = if (generating) Color.White else colors.composerActionFill(canSend)
                val startInk = if (generating) Color.Black else colors.composerActionInk(canSend)
                val ink = if (p == 0f) startInk else lerp(startInk, newInk, p)
                val inset = if (generating) 0.dp else (-1.75).dp * (1f - p)
                Box(Modifier.size(HubStyle.ComposerActionDiameter).offset(x = inset)
                    .composerActionFace().testTag("chat-composer-action-face")
                    .background(fill.copy(alpha = fill.alpha * (1f - p)), CircleShape),
                    contentAlignment = Alignment.Center) {
                    if (generating && p == 0f) {
                        Box(Modifier.size(11.dp).background(Color.Black, RoundedCornerShape(1.25.dp)))
                    } else {
                        Canvas(Modifier.size(20.dp).graphicsLayer { rotationZ = pose.arrowDegrees }) {
                            val stroke = 2.dp.toPx()
                            val r = 6.67.dp.toPx()
                            val wing = 5.67.dp.toPx()
                            val shape = if (generating) p else 1f
                            val tipY: Float
                            if (shape == 1f) {
                                tipY = center.y - r
                                drawLine(ink, Offset(center.x, center.y + r), Offset(center.x, tipY), stroke, StrokeCap.Round)
                            } else {
                                // Narrow the original 11dp stop square into the arrow's rounded stem.
                                val halfWidth = 5.5.dp.toPx() * (1f - shape) + stroke / 2 * shape
                                val halfHeight = 5.5.dp.toPx() * (1f - shape) + (r + stroke / 2) * shape
                                val corner = 1.25.dp.toPx() * (1f - shape) + stroke / 2 * shape
                                drawRoundRect(ink, center - Offset(halfWidth, halfHeight),
                                    Size(2 * halfWidth, 2 * halfHeight), CornerRadius(corner))
                                tipY = center.y - halfHeight + stroke / 2
                            }
                            val tip = Offset(center.x, tipY)
                            drawLine(ink, tip, tip + Offset(-wing, wing) * shape, stroke, StrokeCap.Round)
                            drawLine(ink, tip, tip + Offset(wing, wing) * shape, stroke, StrokeCap.Round)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ToolbarAction(tag: String, label: String, interactive: Boolean, keepEditorFocus: Boolean, enabled: Boolean,
    onClick: () -> Unit, content: @Composable () -> Unit) {
    val inputMode = LocalInputModeManager.current
    val action = if (interactive) Modifier.testTag(tag)
        .then(if (keepEditorFocus) Modifier.focusProperties { canFocus = inputMode.inputMode != InputMode.Touch } else Modifier)
        .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
        .semantics { contentDescription = label } else Modifier.clearAndSetSemantics {}
    Box(Modifier.fillMaxSize().clip(CircleShape).then(action), contentAlignment = Alignment.Center) { content() }
}
