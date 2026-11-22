package io.hydrion.hub.provider

import android.content.Context
import io.hydrion.hub.model.AppRestoreStore
import io.hydrion.hub.model.Mode
import io.hydrion.hub.model.RemoteModel
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

private const val DEEPSEEK_CATALOG_FORMAT = 4
private const val CATALOG_FORMAT = 6

class ProviderStore(context: Context) {
    private val location = AppRestoreStore.current(context)
    private val preferences = context.applicationContext.getSharedPreferences(location.preference("model_catalog_v1"), Context.MODE_PRIVATE)
    internal fun backupCatalog() = stored
    private var storageKey = "catalog"
    private var stored = loadCatalog()
    private var conversationRoutes: Map<Mode, RemoteModel>? = null
    private var assistantDefault: RemoteModel? = null
    internal var onAssistantModelPicked: ((RemoteModel) -> Unit)? = null
    internal var onModelPicked: ((Mode, RemoteModel) -> Unit)? = null
    private val state = MutableStateFlow(stored.forConversation(null))

    internal fun newThreadRoutes(ownerRoute: RemoteModel?): Map<Mode, RemoteModel> {
        val defaults = stored.initialRoutes()
        return if (ownerRoute != null && stored.entries(Mode.Chat).any { it.route == ownerRoute })
            defaults + (Mode.Chat to ownerRoute) else defaults
    }
    internal fun legacyRoutes(): Map<Mode, RemoteModel> = stored.initialRoutes() + stored.active

    internal fun showRoutes(routes: Map<Mode, RemoteModel>?, ownerRoute: RemoteModel?) {
        assistantDefault = ownerRoute
        conversationRoutes = routes
        state.value = stored.forConversation(routes, ownerRoute)
    }

    internal fun setPerChatModel(enabled: Boolean) = update(stored.copy(perChatModel = enabled))

    internal fun moveChatProvider(id: String, direction: Int) {
        val next = stored.movedChatProvider(id, direction)
        if (next !== stored) update(next)
    }

    fun setDefault(mode: Mode, route: RemoteModel): Boolean {
        val next = stored.withDefault(mode, route) ?: return false
        update(next)
        return true
    }

    private fun loadCatalog(): ModelCatalog {
        val raw = preferences.getString("catalog", null) ?: return location.initialSettings()?.catalog ?: demoCatalog().withDeepSeekPreset()
        val json = runCatching { JSONObject(raw) }.getOrNull()
        val loaded = json?.let { runCatching { decodeCatalog(it) }.getOrNull() }
        if (json == null || loaded == null || json.optInt("formatVersion") > CATALOG_FORMAT) {
            // A downgrade may encounter a newer schema. Keep the original and any unknown fields intact.
            storageKey = "catalog_compat_v${CATALOG_FORMAT}_${UUID.nameUUIDFromBytes(raw.toByteArray(Charsets.UTF_8))}"
            val compatible = preferences.getString(storageKey, null)
            return compatible?.let { runCatching { decodeCatalog(it) }.getOrNull() } ?: loaded ?: ModelCatalog(emptyList())
        }
        if (json.optInt("formatVersion") < CATALOG_FORMAT) {
            preferences.edit().putString(storageKey, encodeCatalog(loaded).toString()).apply()
        }
        return loaded
    }
    val catalog = state.asStateFlow()

    // The key is held in this store for the current session.
    private val sessionKeys = mutableMapOf<String, String>()
    fun sessionKey(providerId: String): String = sessionKeys[providerId].orEmpty()

    fun selection(mode: Mode): ModelSelection = state.value.selection(mode)

    fun imagePreview(prompt: String): Result<String>? {
        val current = state.value
        if (current.selectedEntry(Mode.Image) == null) return null
        return runCatching { current.imageRequest(prompt).body.toString(2) }
    }

    internal fun prepareImageGeneration(prompt: String, parentImageLimit: Int? = null): LocalModelRequest {
        val current = state.value
        val invocation = current.imageInvocation(prompt, parentImageLimit)
        val route = requireNotNull(invocation.remoteModel) { "请选择在线图像生成模型。" }
        return current.prepareOfficialImageGeneration(route, prompt, invocation.parameters, ::sessionKey)
    }

    fun configure(mode: Mode, modelId: String, parameters: Map<String, String>) {
        val owner = state.value.providers.singleOrNull { p -> p.mode == mode &&
            p.models.any { it.id == modelId && it.typeFor(p.mode).mode == mode } } ?: return
        configure(mode, RemoteModel(owner.id, modelId), parameters)
    }

    fun configure(mode: Mode, route: RemoteModel, parameters: Map<String, String>) {
        val old = stored
        val entry = old.entries(mode).singleOrNull { it.route == route } ?: return
        val shared = mode == Mode.Chat && !old.perChatModel
        update(old.copy(active = if (shared) old.active else old.active + (mode to route),
            defaults = if (shared && assistantDefault == null) old.defaults + (mode to route) else old.defaults,
            overrides = old.overrides + (route to old.parameters(entry.first, parameters))))
        if (!shared) onModelPicked?.invoke(mode, route)
        else if (assistantDefault != null) onAssistantModelPicked?.invoke(route)
    }

    fun save(provider: Provider, sessionKey: String, defaultModelId: String?) {
        val mode = provider.models.find { it.id == defaultModelId }?.typeFor(provider.mode)?.mode
        val defaults = if (mode != null && defaultModelId != null) mapOf(mode to defaultModelId) else emptyMap()
        saveDefaults(provider, sessionKey, defaults)
    }

    fun saveDefaults(provider: Provider, sessionKey: String, defaults: Map<Mode, String>) {
        sessionKeys[provider.id] = sessionKey
        update(stored.withProvider(provider, defaults))
    }

    internal fun editModel(route: RemoteModel, model: ProviderModel?, makeDefault: Boolean = false) {
        val current = stored
        val edited = current.withEditedModel(route, model, makeDefault)
        if (edited != current) update(edited)
    }

    fun remove(providerId: String) {
        sessionKeys.remove(providerId)
        update(stored.copy(providers = stored.providers.filterNot { it.id == providerId }))
    }

    private fun update(catalog: ModelCatalog) {
        val next = catalog.normalized()
        preferences.edit().putString(storageKey, encodeCatalog(next).toString()).apply()
        stored = next
        showRoutes(conversationRoutes, assistantDefault)
    }
}

private fun <T> JSONArray.mapObjects(transform: (JSONObject) -> T): List<T> =
    (0 until length()).map { transform(getJSONObject(it)) }

private fun JSONObject.stringMap(): Map<String, String> = keys().asSequence().associateWith { getString(it) }

private fun RemoteModel.toJson() = JSONObject().put("providerId", providerId).put("modelId", modelId)
private fun JSONObject.toRoute() = RemoteModel(getString("providerId"), getString("modelId"))

internal fun encodeCatalog(catalog: ModelCatalog): JSONObject = JSONObject().apply {
    put("formatVersion", CATALOG_FORMAT)
    put("perChatModel", catalog.perChatModel)
    put("providers", JSONArray(catalog.providers.map { provider ->
        JSONObject().apply {
            put("id", provider.id)
            put("mode", provider.mode.name)
            put("name", provider.name)
            put("protocol", provider.protocol)
            put("baseUrl", provider.baseUrl)
            put("body", provider.requestBody)
            put("enabled", provider.enabled)
            put("models", JSONArray(provider.models.map { model ->
                JSONObject().apply {
                    put("id", model.id)
                    put("name", model.name)
                    put("profile", model.profile.toStoredJson())
                    put("enabled", model.enabled)
                    put("capabilities", JSONArray(model.capabilities.map { cap ->
                        JSONObject().put("key", cap.key).put("label", cap.label).put("choices", JSONArray(cap.choices))
                    }))
                }
            }))
        }
    }))
    fun routes(values: Map<Mode, RemoteModel>) = JSONObject().apply {
        values.forEach { (mode, route) -> put(mode.name, route.toJson()) }
    }
    put("defaults", routes(catalog.defaults))
    put("active", routes(catalog.active))
    put("overrides", JSONArray(catalog.overrides.map { (route, values) ->
        route.toJson().put("parameters", JSONObject(values))
    }))
}

internal fun decodeCatalog(text: String): ModelCatalog = decodeCatalog(JSONObject(text))

private fun decodeCatalog(json: JSONObject): ModelCatalog {
    val providers = json.getJSONArray("providers").mapObjects { p ->
        Provider(p.getString("id"), Mode.valueOf(p.getString("mode")), p.getString("name"),
            p.getString("protocol"), p.getString("baseUrl"), p.getJSONArray("models").mapObjects { m ->
                ProviderModel(m.getString("id"), m.getString("name"), m.getJSONArray("capabilities").mapObjects { c ->
                    val choices = c.getJSONArray("choices")
                    Capability(c.getString("key"), c.getString("label"), (0 until choices.length()).map { choices.getString(it) })
                }, m.optJSONObject("profile").readProfile(), enabled = m.optBoolean("enabled", true))
            }, p.optString("body", DEFAULT_BODY), enabled = p.optBoolean("enabled", true))
    }
    // Legacy IDs lacked a provider. Bind them once to the same first available entry as the old selector.
    val ordered = providers.sortedByDescending { it.enabled }
    fun legacyRoute(id: String, mode: Mode? = null): RemoteModel? {
        val owner = ordered.firstOrNull { p -> (mode == null || p.mode == mode) &&
            p.models.any { m -> m.id == id && (mode == null || m.typeFor(p.mode).mode == mode) } }
        return owner?.let { RemoteModel(it.id, id) }
    }
    fun routes(key: String): Map<Mode, RemoteModel> {
        val values = json.optJSONObject(key) ?: return emptyMap()
        return Mode.entries.mapNotNull { mode ->
            val route = when (val value = values.opt(mode.name)) {
                is JSONObject -> value.toRoute()
                is String -> legacyRoute(value, mode)
                else -> null
            }
            route?.let { mode to it }
        }.toMap()
    }
    val overrides = when (val raw = json.opt("overrides")) {
        is JSONArray -> raw.mapObjects { it.toRoute() to it.getJSONObject("parameters").stringMap() }.toMap()
        is JSONObject -> raw.keys().asSequence().mapNotNull { id ->
            legacyRoute(id)?.let { it to raw.getJSONObject(id).stringMap() }
        }.toMap()
        else -> emptyMap()
    }
    val catalog = ModelCatalog(providers, routes("defaults"), routes("active"), overrides, json.optBoolean("perChatModel", true)).normalized()
    return if (json.optInt("formatVersion") < DEEPSEEK_CATALOG_FORMAT) catalog.withDeepSeekPreset() else catalog
}
