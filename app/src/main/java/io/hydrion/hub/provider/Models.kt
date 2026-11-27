package io.hydrion.hub.provider

import io.hydrion.hub.model.Mode
import io.hydrion.hub.model.RemoteModel

data class Capability(val key: String, val label: String, val choices: List<String>)

data class ProviderModel(
    val id: String,
    val name: String,
    val capabilities: List<Capability> = emptyList(),
    val profile: ModelProfile = ModelProfile(),
    val enabled: Boolean = true
)

data class Provider(
    val id: String,
    val mode: Mode,
    val name: String,
    val protocol: String,
    val baseUrl: String,
    val models: List<ProviderModel>,
    val requestBody: String = DEFAULT_BODY,
    val enabled: Boolean = true
)

data class ModelSelection(
    val label: String,
    val parameters: Map<String, String>,
    val available: Boolean = true
)

private fun ProviderModel.selectableMode(provider: Provider): Mode? =
    typeFor(provider.mode).mode?.takeIf { it == provider.mode }

internal val Pair<ProviderModel, Provider>.route: RemoteModel
    get() = RemoteModel(second.id, first.id)

data class ModelCatalog(
    val providers: List<Provider>,
    val defaults: Map<Mode, RemoteModel> = emptyMap(),
    val active: Map<Mode, RemoteModel> = emptyMap(),
    val overrides: Map<RemoteModel, Map<String, String>> = emptyMap(),
    val perChatModel: Boolean = true,
    val favorites: Set<RemoteModel> = emptySet(),
) {
    /** Disabling per-chat selection hides the saved pin without modifying the conversation. */
    internal fun forConversation(routes: Map<Mode, RemoteModel>?, assistantDefault: RemoteModel? = null): ModelCatalog {
        val selected = routes ?: active
        val chat = selected[Mode.Chat].takeIf { perChatModel } ?: assistantDefault
        return copy(active = if (chat == null) selected - Mode.Chat else selected + (Mode.Chat to chat))
    }

    internal fun movedChatProvider(id: String, direction: Int): ModelCatalog {
        if (direction != 1 && direction != -1) return this
        val slots = providers.indices.filter { providers[it].mode == Mode.Chat }
        val index = slots.indexOfFirst { providers[it].id == id }
        if (index < 0 || index + direction !in slots.indices) return this
        val moved = providers.toMutableList()
        java.util.Collections.swap(moved, slots[index], slots[index + direction])
        return copy(providers = moved)
    }

    fun entries(mode: Mode): List<Pair<ProviderModel, Provider>> =
        providers.filter { it.enabled && it.mode == mode }.flatMap { provider ->
            provider.models.filter { it.enabled && it.selectableMode(provider) == mode }.map { it to provider }
        }

    fun models(mode: Mode): List<ProviderModel> = entries(mode).map { it.first }

    fun defaultEntry(mode: Mode): Pair<ProviderModel, Provider>? {
        val available = entries(mode)
        val preferred = defaults[mode]
        if (mode == Mode.Chat && preferred != null) return available.singleOrNull { it.route == preferred }
        return available.find { it.route == preferred } ?: available.firstOrNull()
    }

    internal fun initialRoutes(): Map<Mode, RemoteModel> = Mode.entries.mapNotNull { mode ->
        (defaults[mode] ?: defaultEntry(mode)?.route)?.let { mode to it }
    }.toMap()

    internal fun withDefault(mode: Mode, route: RemoteModel): ModelCatalog? {
        if (entries(mode).none { it.route == route }) return null
        return copy(defaults = defaults + (mode to route))
    }

    fun defaultModel(mode: Mode): ProviderModel? = defaultEntry(mode)?.first

    fun selectedEntry(mode: Mode): Pair<ProviderModel, Provider>? = selectedEntry(mode, entries(mode))

    internal fun selectedEntry(mode: Mode, available: List<Pair<ProviderModel, Provider>>): Pair<ProviderModel, Provider>? {
        val preferred = active[mode] ?: defaults[mode]
        return if (preferred == null) available.firstOrNull() else available.singleOrNull { it.route == preferred }
    }

    fun selectedModel(mode: Mode): ProviderModel? = selectedEntry(mode)?.first

    internal fun ownerOf(model: ProviderModel): Provider? =
        providers.filter { p -> p.models.any { it === model } }.singleOrNull()
            ?: providers.filter { p -> p.models.any { it.id == model.id } }.singleOrNull()

    fun parameters(model: ProviderModel): Map<String, String> =
        ownerOf(model)?.let { parameters(model, it) } ?: parameters(model, emptyMap())

    fun parameters(model: ProviderModel, provider: Provider): Map<String, String> =
        parameters(model, overrides[RemoteModel(provider.id, model.id)].orEmpty())

    fun parameters(model: ProviderModel, values: Map<String, String>): Map<String, String> =
        model.configureCapabilities().mapNotNull { cap ->
            cap.choices.firstOrNull()?.let { fallback ->
                cap.key to (values[cap.key]?.takeIf { it in cap.choices } ?: fallback)
            }
        }.toMap()

    fun selection(mode: Mode): ModelSelection {
        val (model, owner) = selectedEntry(mode) ?: return ModelSelection("无可用模型", emptyMap(), false)
        return ModelSelection(model.name, model.requestParameters(parameters(model, owner), mode))
    }

    internal fun withEditedModel(route: RemoteModel, model: ProviderModel?, makeDefault: Boolean = false): ModelCatalog {
        val provider = providers.singleOrNull { it.id == route.providerId } ?: return this
        val original = provider.models.singleOrNull { it.id == route.modelId } ?: return this
        if (model != null && model.id != original.id) return this
        val models = if (model == null) provider.models - original
            else provider.models.map { if (it.id == original.id) model else it }
        val defaultMode = model?.selectableMode(provider)?.takeIf { makeDefault }
        val requested = defaultMode?.let { mapOf(it to route.modelId) }.orEmpty()
        return withProvider(provider.copy(models = models), requested, activateNewDefault = false)
    }

    fun withProvider(provider: Provider, requestedDefaults: Map<Mode, String>): ModelCatalog =
        withProvider(provider, requestedDefaults, activateNewDefault = true)

    private fun withProvider(provider: Provider, requestedDefaults: Map<Mode, String>, activateNewDefault: Boolean): ModelCatalog {
        val updated = if (providers.any { it.id == provider.id }) providers.map {
            if (it.id == provider.id) provider else it
        } else providers + provider
        val valid = requestedDefaults.filter { (mode, id) ->
            provider.models.any { it.id == id && it.selectableMode(provider) == mode &&
                (provider.enabled && it.enabled || defaults[mode] == RemoteModel(provider.id, id)) }
        }.mapValues { (_, id) -> RemoteModel(provider.id, id) }
        val changed = valid.filter { (mode, route) -> defaults[mode] != route }.keys
        val nextActive = if (activateNewDefault) active - changed else {
            // A chat may still be using its implicit default. Keep that choice when editing another model.
            active + changed.filterNot { it in active }.mapNotNull { mode ->
                selectedEntry(mode)?.route?.takeIf { it != valid[mode] }?.let { mode to it }
            }
        }
        return copy(providers = updated, defaults = defaults + valid, active = nextActive).normalized()
    }

    fun normalized(): ModelCatalog {
        // Disabled providers keep their route and parameter preferences until re-enabled.
        val declared = providers.flatMap { p -> p.models.map { m -> RemoteModel(p.id, m.id) to (m to p) } }.toMap()
        // A missing or retyped chat model must stay unavailable until the user chooses a replacement.
        fun keepRoute(mode: Mode, route: RemoteModel): Boolean = mode == Mode.Chat ||
            providers.none { it.id == route.providerId && it.mode != mode }
        val validDefaults = Mode.entries.mapNotNull { mode ->
            val route = defaults[mode]?.takeIf { keepRoute(mode, it) } ?: entries(mode).firstOrNull()?.route
            route?.let { mode to it }
        }.toMap()
        val validOverrides = overrides.mapNotNull { (route, values) ->
            declared[route]?.let { (m, _) -> route to parameters(m, values) }
        }.toMap()
        return copy(defaults = validDefaults, active = active.filter { (mode, route) -> keepRoute(mode, route) },
            overrides = validOverrides, favorites = favorites.intersect(declared.keys))
    }
}

/** Keep the two representations of image dimensions together when both are declared. */
internal fun ProviderModel.choose(current: Map<String, String>, key: String, value: String): Map<String, String> {
    val next = current + (key to value)
    val sizes = capabilities.find { it.key == "size" }?.choices ?: return next
    val ratios = capabilities.find { it.key == "aspect" }?.choices ?: return next
    fun pair(text: String, separator: Char): Pair<Long, Long>? {
        val parts = text.split(separator)
        if (parts.size != 2) return null
        val width = parts[0].toLongOrNull() ?: return null
        val height = parts[1].toLongOrNull() ?: return null
        return if (width > 0 && height > 0) width to height else null
    }
    fun matches(size: String, ratio: String): Boolean {
        val (width, height) = pair(size, 'x') ?: return false
        val (w, h) = pair(ratio, ':') ?: return false
        return width * h == height * w
    }
    return when (key) {
        "size" -> ratios.find { matches(value, it) }?.let { next + ("aspect" to it) }
        "aspect" -> (listOfNotNull(current["size"]) + sizes).find { matches(it, value) }?.let { next + ("size" to it) }
        else -> null
    } ?: next
}

internal const val DEFAULT_BODY = """{
  "model": "{{model}}",
  "prompt": "{{prompt}}",
  "size": "{{size}}",
  "quality": "{{quality}}",
  "n": {{n}}
}"""

internal fun supportedCapabilities(mode: Mode): List<Capability> = when (mode) {
    Mode.Chat -> listOf(
        Capability("effort", "Effort", listOf("Medium", "Low", "High")),
        Capability("speed", "Speed", listOf("Standard", "Fast"))
    )
    Mode.Image -> listOf(
        Capability("size", "图片尺寸", listOf("1024x1024", "1536x1024", "1024x1536")),
        Capability("aspect", "宽高比", listOf("1:1", "3:2", "2:3")),
        Capability("quality", "质量", listOf("Standard", "High")),
        Capability("n", "图片数量", listOf("1", "2", "3", "4"))
    )
}

internal fun demoCatalog(): ModelCatalog = ModelCatalog(
    providers = listOf(
        Provider("demo-chat", Mode.Chat, "本机语言模型", "OpenAI Compatible", "", listOf(
            ProviderModel("demo-reason", "Hydrion", supportedCapabilities(Mode.Chat)),
            ProviderModel("demo-quick", "Hydrion Mini")
        )),
        Provider("demo-image", Mode.Image, "本机图片模型", "OpenAI Images", "", listOf(
            ProviderModel("demo-canvas", "Hydrion Image", supportedCapabilities(Mode.Image))
        ))
    )
).normalized()

internal const val DEEPSEEK_PROVIDER_ID = "deepseek"
internal const val DEEPSEEK_BASE_URL = "https://api.deepseek.com"

internal val deepSeekProvider = Provider(
    DEEPSEEK_PROVIDER_ID, Mode.Chat, "DeepSeek", "OpenAI Responses", DEEPSEEK_BASE_URL,
    listOf(
        deepSeekModel("deepseek-flash", "DeepSeek V4.1 Flash", setOf("text", "image"), listOf("Low", "High", "Max")),
        deepSeekModel("deepseek-v4-pro", "DeepSeek V4 Pro", setOf("text"), listOf("High", "Max"))
    )
)

private fun deepSeekModel(id: String, name: String, input: Set<String>, levels: List<String>) =
    ProviderModel(id, name, profile = ModelProfile(type = ModelType.Chat, externalId = id, input = input,
        output = setOf("text"), abilities = setOf("tools", "reasoning", "structured"),
        levels = levels, allowDisabling = true))

internal fun ModelCatalog.withDeepSeekPreset(): ModelCatalog =
    if (providers.any { it.id == DEEPSEEK_PROVIDER_ID }) this else withProvider(deepSeekProvider, emptyMap())
