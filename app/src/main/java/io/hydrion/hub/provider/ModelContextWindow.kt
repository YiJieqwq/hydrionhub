package io.hydrion.hub.provider

internal const val DEFAULT_CONTEXT_WINDOW_TOKENS = 128_000
internal const val CONTEXT_WINDOW_PARAMETER = "context_window_tokens"

// Both DeepSeek Responses models have a one-million-token window.
internal fun defaultContextWindowTokens(modelId: String): Int =
    when (modelId.trim().lowercase().removePrefix("deepseek/")) {
        "deepseek-flash", "deepseek-v4-flash", "deepseek-v4-pro" -> 1_000_000
        else -> DEFAULT_CONTEXT_WINDOW_TOKENS
    }

internal val ProviderModel.contextWindowTokens: Int
    get() = profile.contextWindowTokens?.takeIf { it > 0 } ?: defaultContextWindowTokens(externalModelId)
