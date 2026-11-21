package io.hydrion.hub.provider

import io.hydrion.hub.model.Mode
import io.hydrion.hub.model.RemoteModel
import org.junit.Assert.*
import org.junit.Test

class ChatTriggerLabelTest {
    private fun model(levels: List<String>, name: String = "Custom model") = ProviderModel("same-id", name,
        capabilities = if (levels.isEmpty()) emptyList() else listOf(Capability("effort", "Effort", levels)))

    private fun catalog(model: ProviderModel) =
        ModelCatalog(listOf(Provider("p", Mode.Chat, "Provider", "custom", "", listOf(model))))

    @Test fun providerScopedEffectiveChoiceIsUsedEvenWhenModelIdsCoincide() {
        val a = model(listOf("Medium", "High", "Low"), "A")
        val b = model(listOf("Turbo", "Fast"), "B")
        val first = catalog(a).providers.single()
        val second = first.copy(id = "q", models = listOf(b))
        val routeA = RemoteModel(first.id, a.id)
        val routeB = RemoteModel(second.id, b.id)
        val activeA = ModelCatalog(listOf(first, second), active = mapOf(Mode.Chat to routeA),
            overrides = mapOf(routeA to mapOf("effort" to "Low"), routeB to mapOf("effort" to "Fast")))
        val low = activeA.selectedChatEffort(activeA.selectedEntry(Mode.Chat))!!
        assertEquals("Low", low.value)
        assertFalse(low.peak)
        val activeB = activeA.copy(active = mapOf(Mode.Chat to routeB))
        val fast = activeB.selectedChatEffort(activeB.selectedEntry(Mode.Chat))!!
        assertEquals("Fast", fast.value)
        assertTrue(fast.peak)
        val high = activeA.copy(overrides = mapOf(routeA to mapOf("effort" to "High")))
        assertTrue(high.selectedChatEffort(high.selectedEntry(Mode.Chat))!!.peak)
    }

    @Test fun effortSpaceUsesTheWidestRenderedChoiceForThisModel() {
        val advances = mapOf("iiiiii" to 15, "高" to 20, "Wide" to 32)
        for (selected in advances.keys) {
            val label = ChatTriggerLabel("Atlas", selected, effortLabels = advances.keys.toList())
            assertEquals("Atlas $selected", label.text)
            assertEquals(32, label.effortWidth(advances::getValue))
        }
        assertEquals(20, ChatTriggerLabel("Fixed", "高").effortWidth(advances::getValue))
        assertEquals(0, ChatTriggerLabel("Plain").effortWidth { error("No effort to measure") })
    }

    @Test fun composerKeepsTheConfiguredSuffixAndPeakColorForFixedAndMissingCapabilities() {
        for ((values, peak) in listOf(listOf("High") to false, listOf("Auto") to false,
            listOf("Ultra") to true, listOf("Off", "High") to false)) {
            val state = catalog(model(values))
            val effort = state.selectedChatEffort(state.selectedEntry(Mode.Chat))!!
            assertEquals(values.first(), effort.value)
            assertEquals(peak, effort.peak)
        }
        val none = catalog(model(emptyList()))
        assertNull(none.selectedChatEffort(none.selectedEntry(Mode.Chat)))
        assertNull(none.selectedChatEffort(null))
        assertEquals("Acme Max", ChatTriggerLabel("Acme Max").text)
    }
}
