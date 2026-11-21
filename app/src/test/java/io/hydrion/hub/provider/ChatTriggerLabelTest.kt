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

    @Test fun heldPreviewFollowsTheRouteAndClearsOnCancelOrLeavingControls() {
        val catalog = catalog(model(listOf("Low", "Medium", "High")))
        val entry = requireNotNull(catalog.selectedEntry(Mode.Chat))
        val picker = ChatModelPickerState()
        fun shown() = requireNotNull(catalog.selectedChatEffort(entry, picker.previewFor(entry.route)))

        picker.open()
        for (choice in listOf("High", "Medium", "High")) {
            picker.previewEffort(entry.route, choice)
            assertEquals(choice, shown().value)
            assertEquals(choice == "High", shown().peak)
            assertEquals("Low", catalog.selection(Mode.Chat).parameters["effort"])
        }
        val other = RemoteModel("other-provider", entry.first.id)
        assertNull(picker.previewFor(other))
        assertNull(ChatModelPickerState().previewFor(entry.route))
        picker.previewEffort(entry.route, null)
        assertEquals("Low", shown().value)

        picker.previewEffort(other, "Medium")
        picker.previewEffort(entry.route, null) // a late cancellation from the old rail
        assertEquals("Medium", picker.previewFor(other))
        picker.models()
        assertNull(picker.previewFor(other))
        picker.open()
        picker.previewEffort(entry.route, "High")
        picker.dismiss()
        picker.previewEffort(entry.route, "High")
        picker.open()
        assertEquals("Low", shown().value)
        assertFalse(shown().peak)
    }

    @Test fun staleOrUnavailablePreviewsCannotOverrideTheModelCapabilities() {
        for (choices in listOf(emptyList(), listOf("Low"), listOf("Low", "Medium"))) {
            val catalog = catalog(model(choices))
            val entry = catalog.selectedEntry(Mode.Chat)
            assertEquals(catalog.selectedChatEffort(entry), catalog.selectedChatEffort(entry, "High"))
        }
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
