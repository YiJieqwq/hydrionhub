package io.hydrion.hub.ui.chrome

import io.hydrion.hub.model.Conversation
import io.hydrion.hub.model.Mode
import io.hydrion.hub.model.Role
import io.hydrion.hub.provider.CONTEXT_WINDOW_PARAMETER
import io.hydrion.hub.provider.ProviderModel
import io.hydrion.hub.provider.contextWindowTokens
import io.hydrion.hub.provider.defaultContextWindowTokens
import java.util.Locale

internal data class ContextUsage(val tokens: Long?, val window: Int, val estimated: Boolean = false) {
    init { require(window > 0) }

    val fraction: Float get() = ((tokens ?: 0L).toDouble() / window).coerceIn(0.0, 1.0).toFloat()
    val high: Boolean get() = tokens != null && tokens >= (window * 4L + 4) / 5
    val label: String? get() = tokens?.let {
        if (estimated) String.format(Locale.ROOT, "%.1fk", it / 1000.0) else "$it tokens"
    }
}

internal fun contextUsage(conversation: Conversation, selectedModel: ProviderModel?): ContextUsage {
    val last = conversation.messages.lastOrNull()
    val window = selectedModel?.contextWindowTokens
        ?: last?.parameters?.get(CONTEXT_WINDOW_PARAMETER)?.toIntOrNull()?.takeIf { it > 0 }
        ?: defaultContextWindowTokens(last?.remoteModel?.modelId.orEmpty())
    if (conversation.mode == Mode.Image) return ContextUsage(0, window, estimated = true)
    // The endpoint's usage already counts the conversation history for that request.
    val reported = conversation.messages.asReversed().asSequence()
        .filter { it.role == Role.Assistant && it.totalTokens != null }
        .maxByOrNull { it.createdAt }
    if (reported != null || last?.remoteModel != null) return ContextUsage(reported?.totalTokens, window)
    return ContextUsage(conversation.messages.size * 260L, window, estimated = true)
}
