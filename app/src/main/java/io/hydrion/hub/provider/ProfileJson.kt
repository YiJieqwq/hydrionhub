package io.hydrion.hub.provider

import org.json.JSONArray
import org.json.JSONObject

/** Names are useful when reopening an editor. Header values, including tokens, stay in memory. */
internal fun ModelProfile.toStoredJson(): JSONObject = JSONObject()
    .put("type", type?.name).put("externalId", externalId).put("icon", icon)
    .put("input", JSONArray(input)).put("output", JSONArray(output)).put("abilities", JSONArray(abilities))
    .put("dialect", dialect).put("levels", JSONArray(levels)).put("defaultLevel", defaultLevel)
    .put("allowDisabling", allowDisabling).put("sampling", sampling)
    .put("reasoningReplay", reasoningReplay).put("replayField", replayField)
    .put("budget", budget).put("imageBudget", imageBudget).put("headerNames", JSONArray(headers.keys))
    .put("requestBody", requestBody).put("imageLinks", imageLinks).put("codeInterpreter", codeInterpreter)
    .put("builtinImage", builtinImage).put("imageModelId", imageModelId).put("contextWindowTokens", contextWindowTokens)

internal fun JSONObject?.readProfile(): ModelProfile {
    if (this == null) return ModelProfile()
    fun strings(key: String): List<String> {
        val items = optJSONArray(key) ?: return emptyList()
        return (0 until items.length()).map { items.getString(it) }
    }
    return ModelProfile(
        type = ModelType.entries.find { it.name == optString("type") },
        externalId = optString("externalId"), icon = optString("icon"),
        input = if (has("input")) strings("input").toSet() else setOf("text"),
        output = strings("output").toSet(), abilities = strings("abilities").toSet(),
        dialect = optString("dialect"), levels = strings("levels"), defaultLevel = optString("defaultLevel"),
        allowDisabling = optBoolean("allowDisabling"), sampling = optString("sampling"),
        reasoningReplay = optString("reasoningReplay"), replayField = optString("replayField"),
        budget = if (isNull("budget")) null else optInt("budget"),
        imageBudget = if (isNull("imageBudget")) null else optInt("imageBudget"),
        headers = strings("headerNames").associateWith { "" },
        requestBody = optString("requestBody"), imageLinks = optBoolean("imageLinks"),
        codeInterpreter = optBoolean("codeInterpreter"), builtinImage = optBoolean("builtinImage"),
        imageModelId = optString("imageModelId"),
        contextWindowTokens = optString("contextWindowTokens").toIntOrNull()?.takeIf { it > 0 }
    )
}
