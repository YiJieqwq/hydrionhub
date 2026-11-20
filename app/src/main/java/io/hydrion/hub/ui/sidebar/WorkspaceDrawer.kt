package io.hydrion.hub.ui.sidebar

import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.*
import androidx.compose.material3.DismissibleNavigationDrawer
import androidx.compose.material3.DrawerState
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.runtime.*
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.hydrion.hub.model.Mode

@Composable
internal fun WorkspaceDrawer(
    mode: Mode, drawer: DrawerState, onClose: () -> Unit, workspaceCovered: Boolean = false,
    drawerContent: @Composable (width: Dp, visible: Boolean) -> Unit,
    content: @Composable () -> Unit
) {
    // Mode can change while editing an empty conversation. Move the same workspace between hosts.
    val workspace = remember { movableContentOf<@Composable () -> Unit> { it() } }
    BoxWithConstraints(Modifier.fillMaxSize().clipToBounds()) {
        val width = if (mode == Mode.Chat) minOf(maxWidth * 0.75f, 348.dp)
            else (LocalConfiguration.current.screenWidthDp.dp - 44.dp).coerceIn(220.dp, 348.dp)
        val widthPx = with(LocalDensity.current) { width.roundToPx() }
        val visible by remember(drawer, widthPx) { derivedStateOf {
            drawer.isOpen || drawer.targetValue == DrawerValue.Open || drawer.currentOffset > -widthPx + 1f
        } }
        if (mode == Mode.Image) {
            ModalNavigationDrawer(drawerState = drawer, drawerContent = { drawerContent(width, visible) }) {
                workspace(content)
            }
        } else {
            // Material measures the body at full width and places it at the moving edge of the sheet.
            DismissibleNavigationDrawer(drawerState = drawer, drawerContent = {
                DrawerPane(disabled = !visible) { drawerContent(width, visible) }
            }) {
                Box(Modifier.fillMaxSize().testTag("drawer-workspace")) {
                    val drawerActive = drawer.isOpen || drawer.targetValue == DrawerValue.Open
                    DrawerPane(disabled = drawerActive || workspaceCovered, modifier = Modifier.fillMaxSize()) { workspace(content) }
                    if (drawerActive) {
                        // This target moves with the body. Only the exposed strip is inside the viewport.
                        Box(Modifier.matchParentSize().clickable(
                            interactionSource = null, indication = null, role = Role.Button, onClick = onClose
                        ).semantics { contentDescription = "关闭侧栏" })
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun DrawerPane(disabled: Boolean, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val focus = LocalFocusManager.current
    var hasFocus by remember { mutableStateOf(false) }
    LaunchedEffect(disabled, hasFocus) {
        if (disabled && hasFocus) focus.clearFocus()
    }
    val barrier = if (disabled) Modifier.clearAndSetSemantics { }.focusProperties {
        enter = { FocusRequester.Cancel }
    } else Modifier
    Box(modifier.onFocusChanged { hasFocus = it.hasFocus }.then(barrier).focusGroup()) { content() }
}
