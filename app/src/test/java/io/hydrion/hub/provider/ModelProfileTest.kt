package io.hydrion.hub.provider

import io.hydrion.hub.model.Mode
import io.hydrion.hub.model.Attachment
import io.hydrion.hub.model.Draft
import org.junit.Assert.*
import org.junit.Test

class ModelProfileTest {
    private val legacy = ProviderModel("legacy", "Legacy", supportedCapabilities(Mode.Chat))
    private val image = ProviderModel("paint", "Paint", profile = ModelProfile(type = ModelType.Image, externalId = "render-v1"))
    private val embed = ProviderModel("embed", "Vector", profile = ModelProfile(type = ModelType.Embedding))
    private val mixed = Provider("mixed", Mode.Chat, "Mixed", "local", "", listOf(legacy, image, embed))

    private val imageProvider = Provider("images", Mode.Image, "Images", "local", "", listOf(image))

    @Test fun onlyModelsFromAnImageProviderCanGenerateImages() {
        val misplaced = image.copy(id = "misplaced")
        val catalog = ModelCatalog(listOf(mixed.copy(models = listOf(legacy, misplaced, embed)), imageProvider),
            defaults = mapOf(Mode.Image to misplaced.id), active = mapOf(Mode.Image to misplaced.id)).normalized()
        assertEquals(listOf(legacy), catalog.models(Mode.Chat))
        assertEquals(listOf(image to imageProvider), catalog.entries(Mode.Image))
        assertEquals(image.id, catalog.defaults[Mode.Image])
        assertNull(catalog.active[Mode.Image])
        assertEquals("render-v1", catalog.selection(Mode.Image).parameters["model"])
        assertEquals("Paint", catalog.selection(Mode.Image).label)
    }

    @Test fun changingTypePrunesStaleSelectionAndCannotReplaceAnotherModesDefault() {
        val other = ProviderModel("other", "Other")
        val start = ModelCatalog(listOf(mixed.copy(models = listOf(legacy, other)), imageProvider),
            defaults = mapOf(Mode.Chat to legacy.id, Mode.Image to image.id), active = mapOf(Mode.Chat to legacy.id))
        val switched = legacy.copy(profile = ModelProfile(type = ModelType.Image))
        val changed = mixed.copy(models = listOf(switched, embed, other))
        val next = start.withProvider(changed, mapOf(Mode.Chat to switched.id))
        assertEquals(other, next.defaultModel(Mode.Chat))
        assertEquals(image, next.defaultModel(Mode.Image))
        assertNull(next.active[Mode.Chat])
        val preferred = next.withProvider(changed, mapOf(Mode.Image to switched.id))
        assertEquals(image.id, preferred.defaults[Mode.Image])
        assertEquals(image, preferred.defaultModel(Mode.Image))
        assertEquals(other, preferred.defaultModel(Mode.Chat))
        assertEquals(preferred.defaults, preferred.withProvider(changed, mapOf(Mode.Chat to embed.id)).defaults)
    }

    @Test fun reasoningLevelsDefaultAndDisableFollowTheSavedProfile() {
        val profile = ModelProfile(type = ModelType.Chat, abilities = setOf("reasoning"), levels = listOf("Low","Max"),
            defaultLevel = "Max", allowDisabling = true, dialect = "OpenAI", budget = 500, sampling = "Default")
        val model = legacy.copy(profile = profile)
        val catalog = ModelCatalog(listOf(mixed.copy(models = listOf(model))))
        assertEquals("Max", catalog.parameters(model)["effort"])
        val options = model.configureCapabilities().first { it.key == "effort" }.choices
        assertEquals(listOf("Max", "Auto", "Low", "Off"), options)
        assertEquals("false", model.requestParameters(catalog.parameters(model, mapOf("effort" to "Off")))["reasoning_enabled"])
        assertEquals("500", catalog.selection(Mode.Chat).parameters["request_budget"])
        val disabled = model.copy(profile = profile.copy(abilities = emptySet()))
        assertFalse(disabled.configureCapabilities().any { it.key == "effort" })
        assertTrue(disabled.configureCapabilities().any { it.key == "speed" })
        assertFalse(model.copy(profile = profile.copy(allowDisabling = false, levels = listOf("Low", "Off")))
            .configureCapabilities().first { it.key == "effort" }.choices.contains("Off"))
        assertEquals(legacy.capabilities, legacy.configureCapabilities())
    }

    @Test fun declaredInputTypesAreEnforcedWhileLegacyAttachmentsStillWork() {
        val draft = Draft(text = "说明", attachments = listOf(Attachment("pdf", "文档.pdf", mimeType = "application/pdf")))
        assertNull(legacy.inputIssue(draft))
        assertNotNull(image.inputIssue(draft))
        val multimodal = image.copy(profile = image.profile.copy(input = setOf("text", "pdf", "image")))
        assertNull(multimodal.inputIssue(draft))
        assertNull(multimodal.inputIssue(Draft(attachments = listOf(Attachment("ref", "图", imageKey = "source")))))
        assertNotNull(multimodal.inputIssue(Draft(attachments = listOf(Attachment("sound", "录音", mimeType = "audio/wav")))))
        assertNotNull(image.copy(profile = image.profile.copy(input = setOf("image"))).inputIssue(Draft(text = "说明")))
    }

    @Test fun missingOrDisabledImageProviderReportsConfigurationError() {
        for (providers in listOf(listOf(mixed), listOf(mixed, imageProvider.copy(enabled = false)))) {
            val catalog = ModelCatalog(providers, active = mapOf(Mode.Image to image.id))
            assertFalse(catalog.selection(Mode.Image).available)
            assertEquals(IMAGE_MODEL_REQUIRED, runCatching { catalog.imageRequest("画一张图") }.exceptionOrNull()?.message)
        }
    }
}
