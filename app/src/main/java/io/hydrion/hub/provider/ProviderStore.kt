package io.hydrion.hub.provider

import android.content.Context
import io.hydrion.hub.model.Mode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

private const val DEEPSEEK_CATALOG_FORMAT = 4

class ProviderStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("model_catalog_v1", Context.MODE_PRIVATE)
    private val state = MutableStateFlow(loadCatalog())

    private fun loadCatalog(): ModelCatalog {
        val json = preferences.getString("catalog", null)?.let { runCatching { JSONObject(it) }.getOrNull() }
        val loaded = json?.let { runCatching { decodeCatalog(it) }.getOrNull() } ?: return demoCatalog().withDeepSeekPreset()
        if (json.optInt("formatVersion") < DEEPSEEK_CATALOG_FORMAT) {
            preferences.edit().putString("catalog", encodeCatalog(loaded).toString()).apply()
        }
        return loaded
    }
    val catalog = state.asStateFlow()

    // The key is held in this store for the current session.
    private val sessionKeys = mutableMapOf<String, String>()
    fun sessionKey(providerId: String): String = sessionKeys[providerId].orEmpty()

    fun selection(mode: Mode): ModelSelection = state.value.selection(mode)

    /** A null result means that the selected language provider has its image tool turned off. */
    fun builtinImagePreview(prompt: String): Result<String>? = renderImageRequest(Mode.Chat, prompt)

    fun imagePreview(prompt: String): Result<String>? = renderImageRequest(Mode.Image, prompt)

    private fun renderImageRequest(mode: Mode, prompt: String): Result<String>? {
        val current = state.value
        val (model, provider) = current.imageTarget(builtin = mode == Mode.Chat) ?: return null
        return runCatching { current.localRequest(model, prompt, provider.requestBody).body.toString(2) }
    }

    fun configure(mode: Mode, modelId: String, parameters: Map<String, String>) {
        val old = state.value
        val model = old.models(mode).find { it.id == modelId } ?: return
        update(old.copy(active = old.active + (mode to modelId),
            overrides = old.overrides + (modelId to old.parameters(model, parameters))))
    }

    fun save(provider: Provider, sessionKey: String, defaultModelId: String?) {
        val mode = provider.models.find { it.id == defaultModelId }?.typeFor(provider.mode)?.mode
        val defaults = if (mode != null && defaultModelId != null) mapOf(mode to defaultModelId) else emptyMap()
        saveDefaults(provider, sessionKey, defaults)
    }

    fun saveDefaults(provider: Provider, sessionKey: String, defaults: Map<Mode, String>) {
        sessionKeys[provider.id] = sessionKey
        update(state.value.withProvider(provider, defaults))
    }

    fun remove(providerId: String) {
        sessionKeys.remove(providerId)
        update(state.value.copy(providers = state.value.providers.filterNot { it.id == providerId }))
    }

    private fun update(catalog: ModelCatalog) {
        val next = catalog.normalized()
        preferences.edit().putString("catalog", encodeCatalog(next).toString()).apply()
        state.value = next
    }
}

private fun <T> JSONArray.mapObjects(transform: (JSONObject) -> T): List<T> =
    (0 until length()).map { transform(getJSONObject(it)) }

private fun JSONObject.stringMap(): Map<String, String> = keys().asSequence().associateWith { getString(it) }

private fun JSONObject.modeMap(key: String): Map<Mode, String> =
    getJSONObject(key).stringMap().mapKeys { Mode.valueOf(it.key) }

internal fun encodeCatalog(catalog: ModelCatalog): JSONObject = JSONObject().apply {
    put("formatVersion", DEEPSEEK_CATALOG_FORMAT)
    put("providers", JSONArray(catalog.providers.map { provider ->
        JSONObject().apply {
            put("id", provider.id)
            put("mode", provider.mode.name)
            put("name", provider.name)
            put("protocol", provider.protocol)
            put("baseUrl", provider.baseUrl)
            put("body", provider.requestBody)
            put("builtinImage", provider.builtinImage)
            put("enabled", provider.enabled)
            put("models", JSONArray(provider.models.map { model ->
                JSONObject().apply {
                    put("id", model.id)
                    put("name", model.name)
                    put("profile", model.profile.toStoredJson())
                    put("capabilities", JSONArray(model.capabilities.map { cap ->
                        JSONObject().put("key", cap.key).put("label", cap.label).put("choices", JSONArray(cap.choices))
                    }))
                }
            }))
        }
    }))
    put("defaults", JSONObject(catalog.defaults.mapKeys { it.key.name }))
    put("active", JSONObject(catalog.active.mapKeys { it.key.name }))
    put("overrides", JSONObject().apply { catalog.overrides.forEach { (id, values) -> put(id, JSONObject(values)) } })
}

internal fun decodeCatalog(text: String): ModelCatalog = decodeCatalog(JSONObject(text))

private fun decodeCatalog(json: JSONObject): ModelCatalog {
    val catalog = ModelCatalog(
        providers = json.getJSONArray("providers").mapObjects { p ->
            Provider(p.getString("id"), Mode.valueOf(p.getString("mode")), p.getString("name"),
                p.getString("protocol"), p.getString("baseUrl"), p.getJSONArray("models").mapObjects { m ->
                    ProviderModel(m.getString("id"), m.getString("name"), m.getJSONArray("capabilities").mapObjects { c ->
                        val choices = c.getJSONArray("choices")
                        Capability(c.getString("key"), c.getString("label"), (0 until choices.length()).map { choices.getString(it) })
                    }, m.optJSONObject("profile").readProfile())
                }, p.optString("body", DEFAULT_BODY), p.optBoolean("builtinImage", false), p.optBoolean("enabled", true))
        },
        defaults = json.modeMap("defaults"),
        active = json.modeMap("active"),
        overrides = json.getJSONObject("overrides").let { obj ->
            obj.keys().asSequence().associateWith { obj.getJSONObject(it).stringMap() }
        }
    ).normalized()
    return if (json.optInt("formatVersion") < DEEPSEEK_CATALOG_FORMAT) catalog.withDeepSeekPreset() else catalog
}
