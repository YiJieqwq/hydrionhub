package io.hydrion.hub.provider

import io.hydrion.hub.model.Mode
import io.hydrion.hub.model.RemoteModel
import org.junit.Assert.*
import org.junit.Test

class ModelCatalogTest {
    @Test fun editingFromThePickerChangesOnlyTheExactProviderAndModel() {
        val a = ProviderModel("shared", "First")
        val b = a.copy(name = "Second")
        val other = ProviderModel("other", "Untouched")
        val first = Provider("a", Mode.Chat, "First provider", "local", "", listOf(a))
        val second = first.copy(id = "b", name = "Updated provider name", models = listOf(b, other))
        val selected = RemoteModel(first.id, a.id)
        val target = RemoteModel(second.id, b.id)
        val original = ModelCatalog(listOf(first, second), defaults = mapOf(Mode.Chat to selected)).normalized()
        val edited = b.copy(name = "Edited")
        val saved = original.withEditedModel(target, edited)
        assertEquals(listOf(first, second.copy(models = listOf(edited, other))), saved.providers)
        assertEquals(original.defaults, saved.defaults)
        assertEquals(selected, saved.selectedEntry(Mode.Chat)?.route)
        assertSame(original, original.withEditedModel(RemoteModel("absent", b.id), edited))
        assertSame(original, original.withEditedModel(target, edited.copy(id = "different")))

        val newDefault = original.withEditedModel(target, edited, makeDefault = true)
        assertEquals(target, newDefault.defaults[Mode.Chat])
        assertEquals(selected, newDefault.selectedEntry(Mode.Chat)?.route)
        val deleted = newDefault.withEditedModel(target, null)
        assertEquals(listOf(first, second.copy(models = listOf(other))), deleted.providers)
        assertEquals(selected, deleted.selectedEntry(Mode.Chat)?.route)
        assertEquals(target, deleted.defaults[Mode.Chat])
        assertNull(deleted.defaultEntry(Mode.Chat))
        val restored = deleted.withProvider(second, emptyMap())
        assertEquals(target, restored.defaultEntry(Mode.Chat)?.route)
        assertEquals(selected, restored.selectedEntry(Mode.Chat)?.route)
    }

    @Test fun parametersOnlyIncludeDeclaredChoices() {
        val model = ProviderModel("m", "任意名称", listOf(Capability("custom", "风格", listOf("墨色", "水彩"))))
        val provider = Provider("p", Mode.Image, "供应商", "custom", "", listOf(model))
        val catalog = ModelCatalog(listOf(provider), overrides = mapOf(RemoteModel("p", "m") to mapOf("custom" to "已移除", "effort" to "High")))
        assertEquals(mapOf("custom" to "墨色"), catalog.selection(Mode.Image).parameters)
        assertFalse(catalog.selection(Mode.Chat).available)
    }

    @Test fun deletingAnExplicitChatModelBlocksUntilTheSameRouteReturns() {
        val catalog = demoCatalog()
        val route = RemoteModel("demo-chat", "demo-reason")
        val remaining = catalog.providers.map { provider ->
            if (provider.mode == Mode.Chat) provider.copy(models = provider.models.filterNot { it.id == route.modelId }) else provider
        }
        val next = catalog.copy(providers = remaining, active = mapOf(Mode.Chat to route)).normalized()
        assertEquals(route, next.active[Mode.Chat])
        assertFalse(next.selection(Mode.Chat).available)
        assertEquals(catalog.selection(Mode.Image), next.selection(Mode.Image))

        val reopened = decodeCatalog(encodeCatalog(next).toString())
        assertEquals(route, reopened.active[Mode.Chat])
        assertNull(reopened.selectedEntry(Mode.Chat))
        assertEquals(route, reopened.copy(providers = catalog.providers).normalized().selectedEntry(Mode.Chat)?.route)
    }

    @Test fun disablingProviderBlocksItsExplicitChatRouteAndReenablingRestoresIt() {
        val original = demoCatalog().copy(active = mapOf(Mode.Chat to RemoteModel("demo-chat", "demo-quick")))
        val fallback = Provider("other", Mode.Chat, "备用", "local", "",
            listOf(ProviderModel("other-chat", "备用模型")))
        val disabled = original.copy(providers = original.providers.map {
            if (it.mode == Mode.Chat) it.copy(enabled = false) else it
        } + fallback).normalized()

        assertEquals(listOf("other-chat"), disabled.models(Mode.Chat).map { it.id })
        assertFalse(disabled.selection(Mode.Chat).available)
        assertEquals(original.active[Mode.Chat], disabled.active[Mode.Chat])
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
        assertEquals(routeA, disabled.defaultEntry(Mode.Chat)?.route)
        assertNull(disabled.selectedEntry(Mode.Chat))
        assertFalse(disabled.selection(Mode.Chat).available)
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
