package io.hydrion.hub.provider

import io.hydrion.hub.model.Draft
import io.hydrion.hub.model.Mode
import io.hydrion.hub.model.RemoteModel
import io.hydrion.hub.model.selectedTool
import org.junit.Assert.*
import org.junit.Test

class ModelSettingsTest {
    @Test fun changingADefaultValidatesEligibilityAndPersistsIndependentlyOfActiveSelections() {
        val catalog = demoCatalog()
        val chat = catalog.entries(Mode.Chat).first()
        val image = catalog.entries(Mode.Image).first()
        val second = chat.second.copy(id = "other-chat", models = listOf(chat.first))
        val off = second.copy(id = "disabled", enabled = false)
        val before = catalog.copy(providers = catalog.providers + second + off,
            active = mapOf(Mode.Chat to chat.route, Mode.Image to image.route))
        val next = RemoteModel(second.id, chat.first.id)
        assertNull(before.withDefault(Mode.Chat, image.route))
        assertNull(before.withDefault(Mode.Image, next))
        assertNull(before.withDefault(Mode.Chat, RemoteModel(off.id, chat.first.id)))
        val after = decodeCatalog(encodeCatalog(requireNotNull(before.withDefault(Mode.Chat, next))).toString())
        assertEquals(next, after.initialRoutes()[Mode.Chat])
        assertEquals(before.initialRoutes()[Mode.Image], after.initialRoutes()[Mode.Image])
        assertEquals(before.active, after.active)
        assertEquals(chat.route, after.selectedEntry(Mode.Chat)?.route)
    }

    @Test fun searchSettingsRoundTripAndRejectInvalidEditsWithoutChangingTheSavedEndpoint() {
        val endpoint = SearchEndpoint("one", " Example ", " https://example.org/search ")
        val settings = requireNotNull(WebSearchSettings(false).withEndpoint(endpoint))
        assertEquals(settings, WebSearchSettings.decode(settings.encode()))
        assertFalse(settings.localEnabled)
        assertEquals("Example", settings.endpoints.single().name)
        for (invalid in listOf("http://example.org", "https://u:p@example.org", "https://example.org?q=x",
                "https://example.org/#fragment", "https://example.org:65536", "file:///tmp/search")) {
            assertNull(settings.withEndpoint(endpoint.copy(url = invalid)))
        }
        val renamed = requireNotNull(settings.withEndpoint(endpoint.copy(name = "Other")))
        assertEquals("Other", renamed.endpoints.single().name)
        assertNull(settings.withEndpoint(endpoint.copy(name = " ")))
        assertEquals("https://example.org/search", settings.endpoints.single().url)
        assertEquals(WebSearchSettings(), WebSearchSettings.decode(null))
        assertEquals(WebSearchSettings(), WebSearchSettings.decode("invalid"))
    }

    @Test fun localSimulationSwitchOnlyGatesLocalSearch() {
        val draft = Draft("search the library", search = "自动")
        assertNotNull(selectedTool(Mode.Chat, draft))
        assertNull(selectedTool(Mode.Chat, draft, allowSearch = false))
        assertNull(selectedTool(Mode.Image, draft))
        assertNull(selectedTool(Mode.Chat, draft.copy(search = "禁用")))
        val draw = draft.copy(text = "draw a tree", imageTool = "自定义")
        assertEquals(selectedTool(Mode.Chat, draw), selectedTool(Mode.Chat, draw, allowSearch = false))
        assertNotNull(selectedTool(Mode.Chat, draw))
    }
}
