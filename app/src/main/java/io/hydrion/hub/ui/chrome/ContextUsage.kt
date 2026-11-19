package io.hydrion.hub.ui.chrome

import io.hydrion.hub.model.Conversation
import io.hydrion.hub.model.Mode
import io.hydrion.hub.model.RemoteModel
import io.hydrion.hub.model.Role
import io.hydrion.hub.model.TokenUsage
import io.hydrion.hub.model.isActive
import io.hydrion.hub.provider.CONTEXT_WINDOW_PARAMETER
import io.hydrion.hub.provider.DEEPSEEK_PROVIDER_ID
import io.hydrion.hub.provider.Provider
import io.hydrion.hub.provider.ProviderModel
import io.hydrion.hub.provider.contextWindowTokens
import io.hydrion.hub.provider.defaultContextWindowTokens
import java.util.Locale

internal data class ContextUsage(
    val tokens: Long?,
    val window: Int,
    val estimated: Boolean = false,
    val usage: TokenUsage? = null
) {
    init { require(window > 0) }

    val fraction: Float get() = ((tokens ?: 0L).toDouble() / window).coerceIn(0.0, 1.0).toFloat()
    val high: Boolean get() = tokens != null && tokens >= (window * 4L + 4) / 5
    val label: String? get() = tokens?.let {
        if (estimated) String.format(Locale.ROOT, "%.1fk", it / 1000.0) else "$it tokens"
    }
}

internal fun contextUsage(conversation: Conversation, selected: Pair<ProviderModel, Provider>?): ContextUsage {
    val messages = conversation.messages
    // A retry replaces an earlier transcript row with a newer request.
    val fallback = if (selected == null) messages.asReversed().maxByOrNull { it.createdAt } else null
    val window = selected?.first?.contextWindowTokens
        ?: fallback?.parameters?.get(CONTEXT_WINDOW_PARAMETER)?.toIntOrNull()?.takeIf { it > 0 }
        ?: defaultContextWindowTokens(fallback?.remoteModel?.modelId.orEmpty())
    if (conversation.mode == Mode.Image) return ContextUsage(0, window, estimated = true)

    val route = selected?.let { (model, provider) -> RemoteModel(provider.id, model.id) } ?: fallback?.remoteModel
    if (route != null && (route.providerId == DEEPSEEK_PROVIDER_ID || messages.any { it.remoteModel == route })) {
        // Keep the previous result during streaming. A terminal response without counts supersedes it.
        val latest = messages.asReversed().asSequence()
            .filter { it.role == Role.Assistant && it.remoteModel == route && !it.status.isActive }
            .maxByOrNull { it.createdAt }
        val usage = latest?.reportedUsage
        return ContextUsage(usage?.contextTokens, window, usage = usage)
    }
    return ContextUsage(messages.size * 260L, window, estimated = true)
}
