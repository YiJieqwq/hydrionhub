package io.hydrion.hub.provider

import androidx.compose.runtime.*
import androidx.lifecycle.ViewModel
import io.hydrion.hub.model.Mode
import java.util.UUID

internal enum class ModelPage(val title: String) { Basic("基础"), Reasoning("推理"), Advanced("高级") }

internal data class HeaderDraft(val id: String, val key: String = "", val value: String = "")

internal data class CapabilityDraft(
    val id: String = UUID.randomUUID().toString(), val key: String = "", val label: String = "", val values: String = ""
)

/** Request header values stay in memory while the activity is recreated. */
internal class ModelFormOwner : ViewModel() {
    private var current: ModelEditDraft? = null

    fun open(model: ProviderModel, mode: Mode, isDefault: Boolean): ModelEditDraft {
        return current?.takeIf { it.initial.id == model.id } ?: ModelEditDraft(model, mode, isDefault).also { current = it }
    }

    fun close() { current = null }
}

internal class ModelEditDraft(val initial: ProviderModel, val mode: Mode, val wasDefault: Boolean) {
    private val initialType = initial.typeFor(mode)
    private val startingProfile = initial.profile.let { p ->
        val effort = initial.capabilities.find { it.key == "effort" }
        val legacy = p.type == null
        val established = legacy && initial.name.isNotBlank()
        val inferredAbilities = buildSet {
            addAll(p.abilities)
            if (legacy && effort != null) add("reasoning")
            if (established && initialType == ModelType.Chat) add("tools")
        }
        p.copy(
            type = initialType, externalId = initial.externalModelId,
            input = if (established) p.input + setOf("text", "image", "audio", "video", "pdf") else p.input,
            output = if (legacy && p.output.isEmpty()) defaultOutput(initialType) else p.output,
            abilities = inferredAbilities,
            levels = if (legacy && p.levels.isEmpty()) effort?.choices.orEmpty().filterNot { it == "Auto" || it == "Off" } else p.levels,
            defaultLevel = if (legacy && p.defaultLevel.isEmpty()) effort?.choices?.firstOrNull().orEmpty() else p.defaultLevel
        )
    }
    private val standard = initialType.mode?.let(::supportedCapabilities).orEmpty().map { it.key }.toSet()
    private val originalChecks = initial.capabilities.filter { it.key in standard && it.key != "effort" }.map { it.key }.toSet()
    private val originalCustom = initial.capabilities.filterNot { it.key in standard }.map {
        CapabilityDraft(id = it.key, key = it.key, label = it.label, values = it.choices.joinToString(", "))
    }
    private val originalHeaders = startingProfile.headers.entries.map { HeaderDraft(it.key, it.key, it.value) }

    var page by mutableStateOf(ModelPage.Basic)
    var profile by mutableStateOf(startingProfile)
    var name by mutableStateOf(initial.name)
    var makeDefault by mutableStateOf(wasDefault)
    var budget by mutableStateOf(startingProfile.budget?.toString().orEmpty())
    var imageBudget by mutableStateOf(startingProfile.imageBudget?.toString().orEmpty())
    var headers by mutableStateOf(originalHeaders)
    var checked by mutableStateOf(originalChecks)
    var custom by mutableStateOf(originalCustom)
    val type: ModelType get() = profile.type ?: initialType
    val currentDefault: Boolean get() = wasDefault && type == initialType

    val dirty get() = name != initial.name || profile != startingProfile || makeDefault != wasDefault ||
        headers != originalHeaders || budget != startingProfile.budget?.toString().orEmpty() ||
        imageBudget != startingProfile.imageBudget?.toString().orEmpty() || checked != originalChecks || custom != originalCustom

    fun changeType(next: ModelType) {
        if (next == type) return
        val outputs = if (profile.output == defaultOutput(type)) defaultOutput(next) else profile.output
        profile = profile.copy(type = next, output = outputs)
        makeDefault = wasDefault && next == initialType
    }

    fun builtinOptions(): List<Capability> = type.mode?.let(::supportedCapabilities).orEmpty().filterNot { it.key == "effort" }

    private fun customCapabilities(): List<Capability> = custom.map {
        Capability(it.key.trim(), it.label.trim(), it.values.split(',', '，').map(String::trim).filter(String::isNotEmpty).distinct())
    }

    fun error(others: List<ProviderModel>): Pair<ModelPage, String>? {
        val id = profile.externalId.trim().ifBlank { name.trim() }
        val label = name.trim().ifBlank { id }
        if (id.isBlank()) return ModelPage.Basic to "请输入模型 ID 或名称"
        if (others.any { it.id != initial.id && it.externalModelId.trim() == id }) return ModelPage.Basic to "供应商中已有相同模型 ID"
        if (others.any { it.id != initial.id && it.name.trim().equals(label, ignoreCase = true) }) return ModelPage.Basic to "供应商中已有同名模型"
        val invalidBudget = listOf(budget, imageBudget).any { it.isNotBlank() && (it.toIntOrNull() ?: -1) < 0 }
        if (invalidBudget) return ModelPage.Advanced to "预算必须是非负整数"
        if (headers.any { !headerName.matches(it.key.trim()) || it.value.any { c -> c == '\n' || c == '\r' } }) {
            return ModelPage.Advanced to "请输入有效的请求头名称和单行值"
        }
        if (headers.map { it.key.trim().lowercase() }.distinct().size < headers.size) return ModelPage.Advanced to "请求头名称重复"
        if (profile.requestBody.isNotBlank() && RequestTemplate.preview(profile.requestBody, null).isFailure) {
            return ModelPage.Advanced to "请求体须为有效的 JSON 对象"
        }
        val extras = customCapabilities()
        if (extras.any { !fieldName.matches(it.key) || it.label.isBlank() || it.choices.isEmpty() }) {
            return ModelPage.Advanced to "请填写有效的自定义字段名、显示名称与可选值"
        }
        val reserved = builtinOptions().map { it.key }.toSet() + "effort"
        if (extras.map { it.key }.distinct().size < extras.size || extras.any { it.key in reserved }) {
            return ModelPage.Advanced to "自定义参数字段名重复"
        }
        if (type == ModelType.Image && extras.any { it.key in RequestTemplate.contextVariables }) {
            return ModelPage.Advanced to "字段名与图片请求模板变量重名"
        }
        return null
    }

    fun buildModel(): ProviderModel {
        if (!dirty) return initial
        val id = profile.externalId.trim().ifBlank { name.trim() }
        val clean = profile.copy(externalId = id, headers = headers.associate { it.key.trim() to it.value },
            budget = budget.toIntOrNull(), imageBudget = imageBudget.toIntOrNull(), requestBody = profile.requestBody.trim())
        val capabilities = buildList {
            if (type == ModelType.Chat && "reasoning" in clean.abilities) {
                add(Capability("effort", "Effort", clean.levels.ifEmpty { listOf("Auto") }))
            }
            addAll(builtinOptions().filter { it.key in checked }.map { option ->
                initial.capabilities.find { it.key == option.key } ?: option
            })
            addAll(customCapabilities())
        }
        return initial.copy(name = name.trim().ifBlank { id }, capabilities = capabilities, profile = clean)
    }

    private fun defaultOutput(type: ModelType) = when (type) {
        ModelType.Image -> setOf("image")
        ModelType.Chat -> setOf("text")
        ModelType.Embedding -> emptySet()
    }
}

private val fieldName = Regex("[A-Za-z_][A-Za-z0-9_]*")
private val headerName = Regex("[!#$%&'*+.^_`|~0-9A-Za-z-]+")
