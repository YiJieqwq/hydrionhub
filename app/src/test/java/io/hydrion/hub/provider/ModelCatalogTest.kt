package io.hydrion.hub.provider

import io.hydrion.hub.model.Mode
import io.hydrion.hub.model.RemoteModel
import org.junit.Assert.*
import org.junit.Test

class ModelCatalogTest {
    @Test fun parametersOnlyIncludeDeclaredChoices() {
        val model = ProviderModel("m", "任意名称", listOf(Capability("custom", "风格", listOf("墨色", "水彩"))))
        val provider = Provider("p", Mode.Image, "供应商", "custom", "", listOf(model))
        val catalog = ModelCatalog(listOf(provider), overrides = mapOf(RemoteModel("p", "m") to mapOf("custom" to "已移除", "effort" to "High")))
        assertEquals(mapOf("custom" to "墨色"), catalog.selection(Mode.Image).parameters)
        assertFalse(catalog.selection(Mode.Chat).available)
    }

    @Test fun deletingSelectedModelFallsBackWithinMode() {
        val catalog = demoCatalog()
        val remaining = catalog.providers.map { p ->
            if (p.mode == Mode.Chat) p.copy(models = p.models.drop(1)) else p
        }
        val next = catalog.copy(providers = remaining, active = mapOf(Mode.Chat to RemoteModel("demo-chat", "demo-reason"))).normalized()
        assertEquals("Hydrion Mini", next.selection(Mode.Chat).label)
        assertEquals(mapOf(CONTEXT_WINDOW_PARAMETER to "128000"), next.selection(Mode.Chat).parameters)
        assertEquals(catalog.selection(Mode.Image), next.selection(Mode.Image))
    }

    @Test fun disablingProviderFallsBackAndReenablingRestoresItsPreferredModel() {
        val original = demoCatalog().copy(active = mapOf(Mode.Chat to RemoteModel("demo-chat", "demo-quick")))
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

    @Test fun sameModelIdKeepsSelectionDefaultsAndCapabilitiesScopedToItsProvider() {
        val a = ProviderModel("shared", "Shared", listOf(Capability("style", "Style", listOf("A1", "A2"))))
        val b = a.copy(capabilities = listOf(Capability("style", "Style", listOf("B1", "B2"))))
        val first = Provider("a", Mode.Chat, "Same", "custom", "", listOf(a))
        val second = first.copy(id = "b", models = listOf(b))
        val routeA = RemoteModel(first.id, a.id)
        val routeB = RemoteModel(second.id, b.id)
        val catalog = ModelCatalog(listOf(first, second), defaults = mapOf(Mode.Chat to routeA),
            active = mapOf(Mode.Chat to routeB), overrides = mapOf(routeA to mapOf("style" to "A2"),
                routeB to mapOf("style" to "B2"))).normalized()
        assertEquals(second.id, catalog.selectedEntry(Mode.Chat)?.second?.id)
        assertEquals("B2", catalog.selection(Mode.Chat).parameters["style"])
        val disabled = catalog.copy(providers = listOf(second.copy(models = listOf(b.copy(enabled = false))), first)).normalized()
        assertEquals(routeA, disabled.selectedEntry(Mode.Chat)?.route)
        assertEquals("A2", disabled.selection(Mode.Chat).parameters["style"])
        assertEquals(routeB, disabled.active[Mode.Chat])
        assertEquals(catalog.overrides, disabled.overrides)
        val disabledOwner = second.copy(enabled = false)
        assertEquals(routeA, disabled.withProvider(disabledOwner, mapOf(Mode.Chat to b.id)).defaults[Mode.Chat])
        val restored = disabled.copy(providers = listOf(second, first)).normalized()
        assertEquals(routeB, restored.selectedEntry(Mode.Chat)?.route)
        assertEquals(routeA, restored.defaultEntry(Mode.Chat)?.route)
        val changedDefault = restored.withProvider(second, mapOf(Mode.Chat to b.id))
        assertEquals(routeB, changedDefault.defaults[Mode.Chat])
        assertNull(changedDefault.active[Mode.Chat])
        assertEquals("B2", changedDefault.selection(Mode.Chat).parameters["style"])
    }

    @Test fun bothModesRequireAMatchingProviderAndUsableCapabilities() {
        val crossChat = ProviderModel("chat", "Chat", profile = ModelProfile(type = ModelType.Chat))
        val crossImage = ProviderModel("image", "Image", profile = ModelProfile(type = ModelType.Image))
        val mixed = listOf(Provider("img", Mode.Image, "I", "OpenAI Images", "", listOf(crossChat)),
            Provider("chat", Mode.Chat, "C", "local", "", listOf(crossImage)))
        val catalog = ModelCatalog(mixed)
        assertTrue(catalog.entries(Mode.Image).isEmpty())
        assertTrue(catalog.entries(Mode.Chat).isEmpty())
        assertEquals(2, catalog.normalized().providers.sumOf { it.models.size })
        val malformed = crossChat.copy(capabilities = listOf(Capability("", "", listOf("x")),
            Capability("style", "Style", listOf("", "A", "A", "B")), Capability("style", "Dup", listOf("C"))))
        assertEquals(listOf(Capability("style", "Style", listOf("A", "B"))), malformed.configureCapabilities())
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

    @Test fun imageTemplateReceivesTheImageModelAndCurrentPrompt() {
        val model = ProviderModel("render", "Local Image", listOf(Capability("style", "风格", listOf("夜景", "胶片"))))
        val sample = RequestTemplate.sample(model, parameters = mapOf("style" to "胶片"), prompt = "云海")
        assertEquals("云海", sample["prompt"])
        assertEquals("Local Image", sample["model"])
        assertEquals("胶片", sample["style"])
        assertEquals(1, sample["n"])
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
