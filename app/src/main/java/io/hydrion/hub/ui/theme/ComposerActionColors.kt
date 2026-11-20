package io.hydrion.hub.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color

internal fun ColorScheme.composerActionFill(accented: Boolean): Color =
    if (accented) primary else surfaceContainerHighest

internal fun ColorScheme.composerActionInk(accented: Boolean): Color =
    if (accented) onPrimary else onSurfaceVariant
