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
            store.sessionKey(provider))
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
    private val initialOrigin = initial.credentialOrigin()
    private val modelHeaderOrigins = initial.models.associate { it.id to initialOrigin }.toMutableMap()
    private var enteredKey by mutableStateOf(initialKey)
    private var keyOrigin by mutableStateOf(initialOrigin)
    private var enteredBeforeAddress by mutableStateOf(false)

    var apiKey: String
        get() = if (enteredBeforeAddress || keyOrigin == provider.credentialOrigin()) enteredKey else ""
        set(value) {
            enteredKey = value
            keyOrigin = provider.credentialOrigin()
            enteredBeforeAddress = keyOrigin == null && value.isNotEmpty()
        }

    // A newly typed key may precede the URL in this form. Bind it when the user fetches or saves.
    fun keyForCurrentAddress(target: Provider = provider): String {
        val origin = provider.credentialOrigin()
        check(target.id == provider.id && target.credentialOrigin() == origin) { "Provider address changed" }
        if (enteredBeforeAddress && origin != null) {
            keyOrigin = origin
            enteredBeforeAddress = false
        }
        return apiKey
    }

    val needsNewCredentials: Boolean
        get() = existing && initialOrigin != provider.credentialOrigin() &&
            (initialKey.isNotEmpty() || initial.models.any { model -> model.profile.headers.values.any { it.isNotEmpty() } })

    var tab by mutableStateOf(EditorTab.Config)
    var editingModel by mutableStateOf<ProviderModel?>(null)

    val dirty: Boolean get() = provider != initial || defaults != initialDefaults || apiKey != initialKey

    fun replaceModels(next: List<ProviderModel>) {
        defaults = defaults.filter { (mode, id) ->
            provider.models.none { it.id == id } || next.any { it.id == id && it.typeFor(provider.mode).mode == mode }
        }
        provider = provider.copy(models = next)
    }

    fun modelForCurrentAddress(model: ProviderModel): ProviderModel {
        val headers = model.profile.headers
        if (headers.values.none { it.isNotEmpty() } || modelHeaderOrigins[model.id] == provider.credentialOrigin()) return model
        return model.copy(profile = model.profile.copy(headers = headers.mapValues { "" }))
    }

    fun providerForSave(): Provider {
        val current = provider
        return current.copy(name = current.name.trim(), protocol = current.protocol.trim(), baseUrl = current.baseUrl.trim(),
            models = current.models.map(::modelForCurrentAddress))
    }

    fun finishModel(model: ProviderModel, makeDefault: Boolean) {
        modelHeaderOrigins[model.id] = provider.credentialOrigin()
        val models = provider.models
        replaceModels(if (models.any { it.id == model.id }) models.map { if (it.id == model.id) model else it }
            else models + model)
        val mode = model.typeFor(provider.mode).mode
        if (makeDefault && mode != null) defaults = defaults + (mode to model.id)
        editingModel = null
    }
}
