package io.hydrion.hub.provider

import io.hydrion.hub.model.Mode
import io.hydrion.hub.model.Draft

enum class ModelType(val mode: Mode?) { Chat(Mode.Chat), Image(Mode.Image), Embedding(null) }

/** A missing type means an older catalog entry inherits its provider's category. */
data class ModelProfile(
    val type: ModelType? = null,
    val externalId: String = "",
    val icon: String = "",
    val input: Set<String> = setOf("text"),
    val output: Set<String> = emptySet(),
    val abilities: Set<String> = emptySet(),
    val dialect: String = "",
    val levels: List<String> = emptyList(),
    val defaultLevel: String = "",
    val allowDisabling: Boolean = false,
    val sampling: String = "",
    val reasoningReplay: String = "",
    val replayField: String = "",
    val budget: Int? = null,
    val imageBudget: Int? = null,
    val headers: Map<String, String> = emptyMap(),
    val requestBody: String = "",
    val imageLinks: Boolean = false,
    val codeInterpreter: Boolean = false,
    val contextWindowTokens: Int? = null
)

fun ProviderModel.typeFor(providerMode: Mode): ModelType =
    profile.type ?: if (providerMode == Mode.Chat) ModelType.Chat else ModelType.Image

val ProviderModel.externalModelId: String get() = profile.externalId.ifBlank { name }

internal fun ProviderModel.inputIssue(draft: Draft): String? {
    if (profile.type == null) return null
    if (draft.text.isNotBlank() && "text" !in profile.input) return "当前模型没有启用文本输入。"
    for (file in draft.attachments) {
        val mime = file.mimeType?.substringBefore(';').orEmpty().lowercase()
        val modality = when {
            file.imageKey != null || mime.startsWith("image/") -> "image"
            mime.startsWith("audio/") -> "audio"
            mime.startsWith("video/") -> "video"
            mime == "application/pdf" || file.name.endsWith(".pdf", true) -> "pdf"
            mime.startsWith("text/") || file.name.endsWith(".txt", true) -> "text"
            else -> return "当前模型没有声明附件「${file.name}」的输入类型。"
        }
        if (modality !in profile.input) return "当前模型没有启用附件「${file.name}」所需的输入类型。"
    }
    return null
}

/** The old Effort capability remains the source of truth for catalogs without a type declaration. */
internal fun ProviderModel.configureCapabilities(): List<Capability> {
    if (profile.type == null) return capabilities
    val rest = capabilities.filterNot { it.key == "effort" }
    if (profile.type != ModelType.Chat || "reasoning" !in profile.abilities) return rest
    val levels = profile.levels.ifEmpty { capabilities.find { it.key == "effort" }?.choices.orEmpty() }
    val choices = buildList {
        add("Auto")
        addAll(levels.filterNot { it == "Auto" || it == "Off" })
        if (profile.allowDisabling) add("Off")
    }.distinct()
    val default = profile.defaultLevel.takeIf { it in choices } ?: "Auto"
    return listOf(Capability("effort", "Effort", listOf(default) + choices.filterNot { it == default })) + rest
}

/** Include only request options that are safe to snapshot in local conversation history. */
internal fun ProviderModel.requestParameters(selected: Map<String, String>, mode: Mode? = profile.type?.mode): Map<String, String> = buildMap {
    putAll(selected)
    if (mode == Mode.Chat) put(CONTEXT_WINDOW_PARAMETER, contextWindowTokens.toString())
    if (profile.externalId.isNotBlank()) put("model", externalModelId)
    if (profile.type == ModelType.Chat) {
        put("reasoning_enabled", ("reasoning" in profile.abilities && selected["effort"] != "Off").toString())
        if ("reasoning" in profile.abilities) {
            if (profile.dialect.isNotBlank()) put("reasoning_dialect", profile.dialect)
            if (profile.sampling.isNotBlank()) put("sampling", profile.sampling)
            if (profile.reasoningReplay.isNotBlank()) put("reasoning_replay", profile.reasoningReplay)
            if (profile.replayField.isNotBlank()) put("replay_field", profile.replayField)
        }
    }
    profile.budget?.let { put("request_budget", it.toString()) }
    profile.imageBudget?.let { put("image_budget", it.toString()) }
}
