package io.hydrion.hub.provider

import io.hydrion.hub.model.Mode
import java.util.UUID

/** All entries are presets for the built-in fake generator. No discovery call is made. */
internal fun localModelPresets(mode: Mode): List<ProviderModel> {
    val supported = supportedCapabilities(mode)
    val additional = when (mode) {
        Mode.Chat -> ProviderModel("local-chat-standard", "Hydrion Chat Standard", supported.filter { it.key == "speed" })
        Mode.Image -> ProviderModel("local-image-lite", "Hydrion Image Lite", supported.filter { it.key in setOf("size", "aspect") })
    }
    return demoCatalog().models(mode) + additional
}

internal data class ModelChoices(val available: List<ProviderModel>, val configured: List<ProviderModel>)

private fun presetId(providerId: String, preset: ProviderModel): String =
    UUID.nameUUIDFromBytes("local-model/$providerId/${preset.id}".toByteArray(Charsets.UTF_8)).toString()

private fun isConfigured(providerId: String, existing: List<ProviderModel>, preset: ProviderModel): Boolean {
    val scopedId = presetId(providerId, preset)
    return existing.any { it.id == scopedId || it.id == preset.id || it.name.trim().equals(preset.name.trim(), ignoreCase = true) }
}

internal fun capabilityLabel(capability: Capability): String = when (capability.key) {
    "effort" -> "推理深度"
    "speed" -> "速度"
    else -> capability.label
}

private val abilityNames = mapOf(
    "tools" to "工具调用", "reasoning" to "推理", "structured" to "结构化输出",
    "structured_output" to "结构化输出"
)

internal fun modelBadges(model: ProviderModel): List<String> =
    (model.configureCapabilities().map(::capabilityLabel) + model.profile.abilities.map { abilityNames[it] ?: it }).distinct()

internal fun localModelChoices(provider: Provider, query: String): ModelChoices {
    val needle = query.trim()
    fun matches(model: ProviderModel): Boolean {
        val typeLabel = when (model.typeFor(provider.mode)) {
            ModelType.Chat -> "Chat 对话"
            ModelType.Image -> "Image 图片"
            ModelType.Embedding -> "Embedding 嵌入"
        }
        val fields = listOf(model.name, model.externalModelId, typeLabel) + modelBadges(model) +
            model.profile.abilities + model.configureCapabilities().flatMap { listOf(it.key, it.label) }
        return fields.any { it.contains(needle, ignoreCase = true) }
    }
    return ModelChoices(
        available = localModelPresets(provider.mode).filter { !isConfigured(provider.id, provider.models, it) && matches(it) },
        configured = provider.models.filter(::matches)
    )
}

/** Preset identities remain distinct across providers and survive local display-name edits. */
internal fun addLocalModels(provider: Provider, selected: Set<String>): List<ProviderModel> {
    val next = provider.models.toMutableList()
    for (preset in localModelPresets(provider.mode)) {
        if (preset.id in selected && !isConfigured(provider.id, next, preset))
            next.add(preset.copy(id = presetId(provider.id, preset)))
    }
    return next
}
