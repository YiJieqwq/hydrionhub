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

    @Test fun typesRouteIndependentlyOfTheProviderAndEmbeddingNeverAppearsInSenders() {
        val catalog = ModelCatalog(listOf(mixed)).normalized()
        assertEquals(listOf(legacy), catalog.models(Mode.Chat))
        assertEquals(listOf(image), catalog.models(Mode.Image))
        assertEquals("render-v1", catalog.selection(Mode.Image).parameters["model"])
        assertEquals("Paint", catalog.selection(Mode.Image).label)
        assertTrue(catalog.copy(providers = listOf(mixed.copy(enabled = false))).entries(Mode.Image).isEmpty())
    }

    @Test fun changingTypePrunesStaleSelectionAndCannotReplaceAnotherModesDefault() {
        val other = ProviderModel("other", "Other")
        val start = ModelCatalog(listOf(mixed.copy(models = mixed.models + other)),
            defaults = mapOf(Mode.Chat to legacy.id, Mode.Image to image.id), active = mapOf(Mode.Chat to legacy.id))
        val switched = legacy.copy(profile = ModelProfile(type = ModelType.Image))
        val changed = mixed.copy(models = listOf(switched, image, embed, other))
        val next = start.withProvider(changed, mapOf(Mode.Chat to switched.id, Mode.Image to image.id))
        assertEquals(other, next.defaultModel(Mode.Chat))
        assertEquals(image, next.defaultModel(Mode.Image))
        assertNull(next.active[Mode.Chat])
        val preferred = next.withProvider(changed, mapOf(Mode.Image to switched.id))
        assertEquals(switched, preferred.defaultModel(Mode.Image))
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

    @Test fun builtinImageResolvesConfiguredTargetAndNeverSelectsAnEmbedding() {
        fun catalog(id: String): ModelCatalog {
            val chat = legacy.copy(profile = ModelProfile(type = ModelType.Chat, builtinImage = true, imageModelId = id))
            return ModelCatalog(listOf(mixed.copy(models = listOf(chat, embed, image))))
        }
        assertEquals(image, catalog(image.id).imageTarget(true)?.first)
        assertEquals(legacy.id, catalog("").imageTarget(true)?.first?.id)
        assertNull(catalog(embed.id).imageTarget(true))
    }
}
