package io.hydrion.hub.model

import io.hydrion.hub.provider.*
import io.hydrion.hub.ui.messages.MessageStyle
import io.hydrion.hub.ui.messages.MessageTone
import io.hydrion.hub.ui.messages.readMessageStyle
import io.hydrion.hub.ui.messages.toPreferences
import org.json.JSONArray
import org.json.JSONObject

private val portableLevels = setOf("Auto", "Off", "Minimal", "Low", "Medium", "High", "Max", "XHigh")

/** Only this projection crosses the device boundary. No request template, header or arbitrary endpoint is serialized. */
internal fun ModelCatalog.portableCatalog(): ModelCatalog {
    val safeProviders = providers.map { p ->
        val protocol = (listOf("local", "OpenAI Compatible", "OpenAI Images") + ChatProtocol.entries.map { it.label })
            .find { it.equals(p.protocol.trim(), ignoreCase = true) }.orEmpty()
        val url = p.baseUrl.trimEnd('/').takeIf { it in setOf("https://api.openai.com", "https://api.openai.com/v1",
            DEEPSEEK_BASE_URL, "$DEEPSEEK_BASE_URL/v1", "wss://api.openai.com/v1/responses") }.orEmpty()
        val local = p.baseUrl.isBlank() && (protocol.equals("local", true) || p.id in setOf("demo-image", "demo-chat", DEEPSEEK_PROVIDER_ID))
        Provider(p.id, p.mode, p.name, protocol, url, p.models.map { m ->
            val source = m.profile
            val levels = source.levels.filter { it in portableLevels }
            val profile = ModelProfile(type = source.type, externalId = source.externalId,
                levels = levels, defaultLevel = source.defaultLevel.takeIf { it in portableLevels }.orEmpty(),
                allowDisabling = source.allowDisabling,
                input = source.input.intersect(setOf("text", "image", "audio", "video", "pdf")),
                output = source.output.intersect(setOf("text", "image", "audio", "video")),
                abilities = source.abilities.intersect(setOf("tools", "reasoning", "structured_output", "structured")),
                contextWindowTokens = source.contextWindowTokens, budget = source.budget, imageBudget = source.imageBudget)
            val caps = supportedCapabilities(p.mode).mapNotNull { preset ->
                val choices = m.capabilities.find { it.key == preset.key }?.choices.orEmpty().filter { it in preset.choices }
                if (choices.isEmpty()) null else preset.copy(choices = choices)
            }
            ProviderModel(m.id, m.name, caps, profile, m.enabled)
        }, enabled = p.enabled && (local || url.isNotEmpty()))
    }
    val safeOverrides = buildMap {
        for (provider in safeProviders) for (model in provider.models) {
            val route = RemoteModel(provider.id, model.id)
            val values = overrides[route] ?: continue
            val selected = model.configureCapabilities().mapNotNull { capability ->
                values[capability.key]?.takeIf { it in capability.choices }?.let { capability.key to it }
            }.toMap()
            if (selected.isNotEmpty()) put(route, selected)
        }
    }
    return ModelCatalog(safeProviders, defaults, active, safeOverrides, perChatModel).normalized()
}

internal data class AppBackupSettings(val catalog: ModelCatalog, val appearance: MessageStyle) {
    fun encode() = JSONObject().put("catalog", encodeCatalog(catalog.portableCatalog()))
        .put("appearance", JSONObject(appearance.toPreferences(includeChoices = false)))
        // Older readers strictly validate the fields inside appearance but ignore sibling metadata.
        .put("chosenUserPalettes", JSONArray(MessageTone.entries.filter { it in appearance.chosenUserPalettes }.map { it.name }))
        .apply { if (appearance.colorCodeReasoningEffort) put("colorCodeReasoningEffort", true) }

    companion object {
        fun decode(json: JSONObject): AppBackupSettings {
            val raw = json.getJSONObject("catalog")
            require(raw.get("formatVersion") == 6)
            val rows = raw.getJSONArray("providers")
            val ids = mutableSetOf<String>()
            for (i in 0 until rows.length()) {
                val p = rows.getJSONObject(i)
                require(ids.add(p.getString("id")) && p.getString("id").isNotBlank())
                val models = p.getJSONArray("models")
                val keys = mutableSetOf<String>()
                for (j in 0 until models.length()) {
                    val id = models.getJSONObject(j).getString("id")
                    require(id.isNotBlank() && keys.add(id))
                }
            }
            val appearance = json.getJSONObject("appearance")
            val fields = appearance.keys().asSequence().associateWith { appearance.getString(it) }
            val style = readMessageStyle(fields).copy(colorCodeReasoningEffort =
                json.has("colorCodeReasoningEffort") && json.getBoolean("colorCodeReasoningEffort"))
            require(style.toPreferences(includeChoices = false) == fields)
            val restored = if (json.has("chosenUserPalettes")) {
                val list = json.getJSONArray("chosenUserPalettes")
                val tones = (0 until list.length()).map { MessageTone.valueOf(list.getString(it)) }.toSet()
                require(tones.size == list.length())
                style.copy(chosenUserPalettes = tones)
            } else style
            return AppBackupSettings(decodeCatalog(raw.toString()).portableCatalog(), restored)
        }
    }
}

/** Deleted model definitions cannot be reconstructed from history. Preserve their IDs and flag them in preview. */
internal fun InspectedChatArchive.missingModelReferences(): Int {
    val catalog = settings?.catalog ?: return 0
    val known = catalog.providers.flatMap { p -> p.models.map { RemoteModel(p.id, it.id) } }.toSet()
    val used = buildSet {
        addAll(catalog.defaults.values)
        addAll(catalog.active.values)
        workspace.assistants.mapNotNullTo(this) { it.chatModel }
        for (chat in workspace.history + workspace.pending) {
            addAll(chat.modelRoutes.orEmpty().values)
            for (message in chat.allStoredMessages()) {
                message.remoteModel?.let(::add)
                message.imageInvocation?.remoteModel?.let(::add)
            }
        }
    }
    return (used - known).size
}
