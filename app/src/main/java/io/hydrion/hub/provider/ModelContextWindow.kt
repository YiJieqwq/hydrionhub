package io.hydrion.hub.provider

internal const val DEFAULT_CONTEXT_WINDOW_TOKENS = 128_000
internal const val CONTEXT_WINDOW_PARAMETER = "context_window_tokens"
// Legacy snapshots also used 128k for the generic editor suggestion.
internal const val CONFIGURED_WINDOW_PARAMETER = "context_window_configured"

// Both DeepSeek Responses models have a one-million-token window.
internal fun namedContextWindowTokens(modelId: String): Int? =
    when (modelId.trim().lowercase().removePrefix("deepseek/")) {
        "deepseek-flash", "deepseek-v4-flash", "deepseek-v4-pro" -> 1_000_000
        else -> null
    }

internal fun defaultContextWindowTokens(modelId: String): Int =
    namedContextWindowTokens(modelId) ?: DEFAULT_CONTEXT_WINDOW_TOKENS

internal val ProviderModel.knownContextWindowTokens: Int?
    get() = profile.contextWindowTokens?.takeIf { it > 0 } ?: namedContextWindowTokens(externalModelId)

internal val ProviderModel.contextWindowTokens: Int
    get() = knownContextWindowTokens ?: DEFAULT_CONTEXT_WINDOW_TOKENS
