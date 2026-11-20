package io.hydrion.hub.provider

import io.hydrion.hub.model.Mode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChatSpeedControlsTest {
    private val local = demoCatalog().providers.first { it.mode == Mode.Chat }
    private val model = local.models.first()

    @Test fun localFastCanToggleToANeutralDeclaredChoice() {
        val controls = model.chatSpeedControls(local, mapOf("speed" to "Fast"))
        assertEquals("Fast", controls.fast)
        assertEquals("Standard", controls.off)
        assertEquals("Fast", controls.selected)
        assertNull(controls.ultra)
        assertNull(model.chatSpeedControls(local, mapOf("speed" to "Standard")).selected)
    }

    @Test fun aSavedOrDeclaredTierDoesNotGrantTheTransportThatTier() {
        val custom = model.copy(capabilities = listOf(Capability("speed", "Speed",
            listOf("Standard", "Fast", "Ultrafast"))))
        assertNull(custom.chatSpeedControls(local, mapOf("speed" to "Ultrafast")).selected)
        for (protocol in ChatProtocol.entries) {
            val remote = local.copy(id = "remote", protocol = protocol.label, baseUrl = "https://api.openai.com/v1")
            assertEquals(ChatSpeedControls(), custom.chatSpeedControls(remote, mapOf("speed" to "Fast")))
        }
        assertEquals(ChatSpeedControls(), local.models.last().chatSpeedControls(local, mapOf("speed" to "Fast")))
    }

    @Test fun aFixedLocalRequestBodyCannotBeOverriddenFromThePicker() {
        val forced = model.copy(profile = model.profile.copy(requestBody = """{"speed":"Fast"}"""))
        val fast = forced.chatSpeedControls(local, mapOf("speed" to "Standard"))
        assertEquals("Fast", fast.selected)
        assertNull(fast.off)
        val slow = model.copy(profile = model.profile.copy(requestBody = """{"speed":"Standard"}"""))
            .chatSpeedControls(local, mapOf("speed" to "Fast"))
        assertNull(slow.selected)
        assertNull(slow.off)
    }
}
