package io.hydrion.hub.provider

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import io.hydrion.hub.model.Mode

internal class ProviderNavigation : ViewModel() {
    var mode by mutableStateOf<Mode?>(null)
    var edit by mutableStateOf<ProviderDraft?>(null)

    fun open(provider: Provider, store: ProviderStore) {
        val catalog = store.catalog.value
        edit = ProviderDraft(provider, catalog.providers.any { it.id == provider.id },
            catalog.defaults.filterValues { it.providerId == provider.id }.mapValues { it.value.modelId },
            store.sessionKey(provider.id))
    }
}

internal enum class EditorTab { Config, Models }

/** Both tabs edit this in-memory transaction. Only an explicit Save updates the catalog or key. */
internal class ProviderDraft(
    val initial: Provider,
    val existing: Boolean,
    private val initialDefaults: Map<Mode, String>,
    private val initialKey: String
) {
    var provider by mutableStateOf(initial)
    var defaults by mutableStateOf(initialDefaults)
        private set
    var apiKey by mutableStateOf(initialKey)
    var tab by mutableStateOf(EditorTab.Config)
    var editingModel by mutableStateOf<ProviderModel?>(null)

    val dirty: Boolean get() = provider != initial || defaults != initialDefaults || apiKey != initialKey

    fun replaceModels(next: List<ProviderModel>) {
        defaults = defaults.filter { (mode, id) ->
            provider.models.none { it.id == id } || next.any { it.id == id && it.typeFor(provider.mode).mode == mode }
        }
        provider = provider.copy(models = next)
    }

    fun finishModel(model: ProviderModel, makeDefault: Boolean) {
        val models = provider.models
        replaceModels(if (models.any { it.id == model.id }) models.map { if (it.id == model.id) model else it }
            else models + model)
        val mode = model.typeFor(provider.mode).mode
        if (makeDefault && mode != null) defaults = defaults + (mode to model.id)
        editingModel = null
    }
}
