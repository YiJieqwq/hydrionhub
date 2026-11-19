package io.hydrion.hub.provider

import io.hydrion.hub.model.Mode
import org.junit.Assert.*
import org.junit.Test

class ModelCatalogTest {
    @Test fun parametersOnlyIncludeDeclaredChoices() {
        val model = ProviderModel("m", "任意名称", listOf(Capability("custom", "风格", listOf("墨色", "水彩"))))
        val provider = Provider("p", Mode.Image, "供应商", "custom", "", listOf(model))
        val catalog = ModelCatalog(listOf(provider), overrides = mapOf("m" to mapOf("custom" to "已移除", "effort" to "High")))
        assertEquals(mapOf("custom" to "墨色"), catalog.selection(Mode.Image).parameters)
        assertFalse(catalog.selection(Mode.Chat).available)
    }

    @Test fun deletingSelectedModelFallsBackWithinMode() {
        val catalog = demoCatalog()
        val remaining = catalog.providers.map { p ->
            if (p.mode == Mode.Chat) p.copy(models = p.models.drop(1)) else p
        }
        val next = catalog.copy(providers = remaining, active = mapOf(Mode.Chat to "demo-reason")).normalized()
        assertEquals("Hydrion Mini", next.selection(Mode.Chat).label)
        assertEquals(mapOf(CONTEXT_WINDOW_PARAMETER to "128000"), next.selection(Mode.Chat).parameters)
        assertEquals(catalog.selection(Mode.Image), next.selection(Mode.Image))
    }

    @Test fun disablingProviderFallsBackAndReenablingRestoresItsPreferredModel() {
        val original = demoCatalog().copy(active = mapOf(Mode.Chat to "demo-quick"))
        val fallback = Provider("other", Mode.Chat, "备用", "local", "",
            listOf(ProviderModel("other-chat", "备用模型")))
        val disabled = original.copy(providers = original.providers.map {
            if (it.mode == Mode.Chat) it.copy(enabled = false) else it
        } + fallback).normalized()

        assertEquals(listOf("other-chat"), disabled.models(Mode.Chat).map { it.id })
        assertEquals("备用模型", disabled.selection(Mode.Chat).label)
        assertFalse(disabled.copy(providers = disabled.providers - fallback).normalized().selection(Mode.Chat).available)
        assertEquals(original.selection(Mode.Image), disabled.selection(Mode.Image))
        val restored = disabled.copy(providers = original.providers + fallback).normalized()
        assertEquals("Hydrion Mini", restored.selection(Mode.Chat).label)
    }

    @Test fun changingImageSizeKeepsSupportedAspectInSync() {
        val catalog = demoCatalog()
        val model = requireNotNull(catalog.selectedModel(Mode.Image))
        val landscape = model.choose(catalog.parameters(model), "size", "1536x1024")
        assertEquals("3:2", landscape["aspect"])
        assertEquals("1024x1536", model.choose(landscape, "aspect", "2:3")["size"])
    }

    @Test fun variablesEscapeUserTextAndPreserveUnquotedNumberTypes() {
        val actual = RequestTemplate.substitute(
            """{"p":"art: {{prompt}}","n":{{n}},"size":{{size}}}""",
            mapOf("prompt" to "a\"\nb", "n" to 2, "size" to "1024x1024"))
        assertEquals("""{"p":"art: a\"\nb","n":2,"size":"1024x1024"}""", actual)
    }

    @Test fun builtinImageTemplateReceivesTheSelectedModelAndCurrentPrompt() {
        val model = ProviderModel("language", "Local Chat", listOf(Capability("style", "风格", listOf("夜景", "胶片"))))
        val sample = RequestTemplate.sample(model, parameters = mapOf("style" to "胶片"), prompt = "云海")
        assertEquals("云海", sample["prompt"])
        assertEquals("Local Chat", sample["model"])
        assertEquals("胶片", sample["style"])
        assertEquals(1, sample["n"])
        assertTrue(demoCatalog().providers.first { it.mode == Mode.Chat }.builtinImage)
    }

    @Test fun rejectsInvalidEscapesAndLiteralControlCharactersInJsonText() {
        val invalid = listOf("""{"prompt":"a\q"}""", """{"prompt":"a\u03g2"}""", "{\"prompt\":\"x\ny\"}")
        invalid.forEach { assertTrue(runCatching { RequestTemplate.substitute(it, emptyMap()) }.isFailure) }
        val valid = """{"prompt":"\"\\\/\b\f\n\r\t\u97f3"}"""
        assertEquals(valid, RequestTemplate.substitute(valid, emptyMap()))
    }

    @Test fun rejectsNonJsonLiteralsAcceptedByTheAndroidReader() {
        listOf("True", "FALSE", "NuLl", "01", "1e", "+4").forEach { token ->
            assertTrue(runCatching { RequestTemplate.substitute("""{"v":$token}""", emptyMap()) }.isFailure)
        }
        val valid = """{"numbers":[-1.5E+3,0,2e-2],"values":[true,false,null]}"""
        assertEquals(valid, RequestTemplate.substitute(valid, emptyMap()))
    }

    @Test fun unknownVariableIsReported() {
        val error = runCatching { RequestTemplate.substitute("""{"n":{{typo}}}""", emptyMap()) }.exceptionOrNull()
        assertTrue(error?.message.orEmpty().contains("typo"))
    }
}
