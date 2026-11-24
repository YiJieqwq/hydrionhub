package io.hydrion.hub.ui.messages

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageStyleTest {
    @Test fun choosingAnUnchangedPresetSurvivesReloadOnlyForItsToneAndResetReturnsToAutomatic() {
        val fresh = MessageStyle()
        val otherSettings = fresh.copy(assistantBubble = true)
        assertTrue(readMessageStyle(otherSettings.toPreferences()).usesAutomaticUserTint)
        val selected = fresh.withPalette(MessageTone.Dark, fresh.dark, userSelected = true)
        val persisted = readMessageStyle(selected.toPreferences())
        assertFalse(persisted.usesAutomaticUserTint)
        assertTrue(persisted.copy(tone = MessageTone.Light).usesAutomaticUserTint)
        assertEquals(fresh, readMessageStyle(fresh.toPreferences(), fallback = persisted))
    }

    @Test fun firstInstallUsesRequestedDarkPaletteWithoutAnAssistantBubble() {
        val restored = readMessageStyle(emptyMap<String, String>())
        assertEquals(MessageTone.Dark, restored.tone)
        assertEquals(CardMaterial.Solid, restored.material)
        assertFalse(restored.assistantBubble)
        assertEquals(MessageCardStyle("#242628", "#3B3F44", "#FFFFFF"), restored.palette.assistant)
        assertEquals(MessageCardStyle("#020562", "#030776", "#D3F0FF"), restored.palette.user)
    }

    @Test fun eachToneAndRoleSurvivesRoundTripIndependently() {
        val initial = MessageStyle()
        val edited = initial.copy(tone = MessageTone.Light, material = CardMaterial.Frosted, assistantBubble = true)
            .withPalette(MessageTone.Light, initial.light.copy(user = initial.light.user.copy(text = "#020202")))
            .withPalette(MessageTone.Dark, initial.dark.copy(assistant = initial.dark.assistant.copy(border = "#ABC123")))
        val loaded = readMessageStyle(edited.toPreferences())
        assertEquals(edited, loaded)
        assertEquals("#020202", loaded.palette.user.text)
        assertEquals(initial.light.assistant, loaded.palette.assistant)
        assertEquals("#ABC123", loaded.copy(tone = MessageTone.Dark).palette.assistant.border)
        assertEquals(initial.dark.user, loaded.copy(tone = MessageTone.Dark).palette.user)
    }

    @Test fun partialOrDamagedPreferencesRecoverEachFieldSeparately() {
        val partial = mapOf("tone" to "Unknown", "material" to "Solid", "dark.user.background" to " invalid ",
            "dark.user.text" to "  abcd09 ", "light.assistant.text" to 45, "assistantBubble" to "maybe")
        val restored = readMessageStyle(partial)
        assertEquals(MessageTone.Dark, restored.tone)
        assertEquals(CardMaterial.Solid, restored.material)
        assertEquals("#020562", restored.dark.user.background)
        assertEquals("#ABCD09", restored.dark.user.text)
        assertEquals(MessageStyle().light.assistant.text, restored.light.assistant.text)
        assertFalse(restored.assistantBubble)
    }

    @Test fun defaultUsesTheThemeUntilACustomMaterialIsSelectedAgain() {
        val custom = MessageStyle().let {
            it.withPalette(MessageTone.Dark, it.dark.copy(assistant = it.dark.assistant.copy(text = "#FF3300")))
        }
        val default = custom.copy(material = CardMaterial.Default)
        assertEquals("#F5F5F5", default.renderPalette.assistant.text)
        assertEquals("#202124", default.copy(tone = MessageTone.Light).renderPalette.assistant.text)
        assertEquals(custom.palette, default.copy(material = CardMaterial.Frosted).renderPalette)
        assertEquals(custom.palette, readMessageStyle(default.toPreferences()).copy(material = CardMaterial.Solid).renderPalette)
    }

    @Test fun invalidUpdatesKeepThePreviousValidChoice() {
        val prior = MessageStyle().let { it.copy(dark = it.dark.copy(user = it.dark.user.copy(text = "#FEDCBA"))) }
        val candidate = prior.toPreferences() + ("dark.user.text" to "#12XX45")
        assertEquals(prior, readMessageStyle(candidate, fallback = prior))
        assertEquals("#AABB00", normalizeMessageColor("  #aAbb00 "))
        assertNull(normalizeMessageColor("#ABC"))
        assertNull(normalizeMessageColor("#FF001122"))
    }
}
