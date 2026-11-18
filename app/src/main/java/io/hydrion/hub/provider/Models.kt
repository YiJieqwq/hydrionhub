package io.hydrion.hub.provider

import io.hydrion.hub.model.Mode

data class Capability(val key: String, val label: String, val choices: List<String>)

data class ProviderModel(
    val id: String,
    val name: String,
    val capabilities: List<Capability> = emptyList(),
    val profile: ModelProfile = ModelProfile()
)

data class Provider(
    val id: String,
    val mode: Mode,
    val name: String,
    val protocol: String,
    val baseUrl: String,
    val models: List<ProviderModel>,
    val requestBody: String = DEFAULT_BODY,
    val builtinImage: Boolean = false,
    val enabled: Boolean = true
)

data class ModelSelection(
    val label: String,
    val parameters: Map<String, String>,
    val available: Boolean = true
)

data class ModelCatalog(
    val providers: List<Provider>,
    val defaults: Map<Mode, String> = emptyMap(),
    val active: Map<Mode, String> = emptyMap(),
    val overrides: Map<String, Map<String, String>> = emptyMap()
) {
    fun entries(mode: Mode): List<Pair<ProviderModel, Provider>> = providers.filter { it.enabled }.flatMap { provider ->
        provider.models.filter { it.typeFor(provider.mode).mode == mode }.map { it to provider }
    }

    fun models(mode: Mode): List<ProviderModel> = entries(mode).map { it.first }

    fun defaultModel(mode: Mode): ProviderModel? {
        val options = models(mode)
        return options.find { it.id == defaults[mode] } ?: options.firstOrNull()
    }

    fun selectedModel(mode: Mode): ProviderModel? =
        models(mode).find { it.id == active[mode] } ?: defaultModel(mode)

    fun parameters(model: ProviderModel, values: Map<String, String> = overrides[model.id].orEmpty()): Map<String, String> =
        model.configureCapabilities().mapNotNull { cap ->
            cap.choices.firstOrNull()?.let { fallback ->
                cap.key to (values[cap.key]?.takeIf { it in cap.choices } ?: fallback)
            }
        }.toMap()

    fun selection(mode: Mode): ModelSelection {
        val model = selectedModel(mode) ?: return ModelSelection("无可用模型", emptyMap(), false)
        return ModelSelection(model.name, model.requestParameters(parameters(model)))
    }

    fun withProvider(provider: Provider, requestedDefaults: Map<Mode, String>): ModelCatalog {
        val updated = if (providers.any { it.id == provider.id }) providers.map {
            if (it.id == provider.id) provider else it
        } else providers + provider
        val valid = requestedDefaults.filter { (mode, id) ->
            provider.models.any { it.id == id && it.typeFor(provider.mode).mode == mode }
        }
        val changed = valid.filter { (mode, id) -> defaults[mode] != id }.keys
        return copy(providers = updated, defaults = defaults + valid, active = active - changed).normalized()
    }

    fun normalized(): ModelCatalog {
        val validModels = providers.flatMap { it.models }
        // A disabled provider keeps its preferences for the next time it is enabled.
        val declared = providers.flatMap { provider ->
            provider.models.mapNotNull { model -> model.typeFor(provider.mode).mode?.let { it to model } }
        }.groupBy({ it.first }, { it.second })
        val validDefaults = declared.mapNotNull { (mode, entries) ->
            val saved = entries.find { it.id == defaults[mode] }
            (saved ?: defaultModel(mode))?.let { mode to it.id }
        }.toMap()
        val validActive = active.filter { (mode, id) -> declared[mode].orEmpty().any { it.id == id } }
        val validOverrides = validModels.filter { it.id in overrides }.associate { it.id to parameters(it) }
        return copy(defaults = validDefaults, active = validActive, overrides = validOverrides)
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
        ), builtinImage = true),
        Provider("demo-image", Mode.Image, "本机图片模型", "OpenAI Images", "", listOf(
            ProviderModel("demo-canvas", "Hydrion Image", supportedCapabilities(Mode.Image))
        ))
    )
).normalized()
