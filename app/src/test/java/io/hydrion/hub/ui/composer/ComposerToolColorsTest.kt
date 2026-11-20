package io.hydrion.hub.ui.composer

import androidx.compose.ui.graphics.toArgb
import org.junit.Assert.assertEquals
import org.junit.Test

class ComposerToolColorsTest {
    @Test fun onlyAnEnabledSmartTerminalUsesTheAmberInk() {
        assertEquals(0xFFB6C4FF.toInt(), ChatToolInk.toArgb())
        assertEquals(ChatToolInk, chatTerminalInk(false))
        assertEquals(0xFFC27B13.toInt(), chatTerminalInk(true).toArgb())
    }
}
