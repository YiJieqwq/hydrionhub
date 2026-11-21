package io.hydrion.hub.ui.composer

import androidx.compose.ui.graphics.Color

internal val ChatToolInk = Color.White

internal fun chatTerminalInk(smartTerminalEnabled: Boolean): Color =
    if (smartTerminalEnabled) Color(0xFFC27B13) else Color(0xFFB6C4FF)
