package io.hydrion.hub.ui.chrome

import io.hydrion.hub.model.Conversation
import io.hydrion.hub.model.Message
import io.hydrion.hub.model.Mode
import io.hydrion.hub.model.RemoteModel
import io.hydrion.hub.model.Role
import io.hydrion.hub.model.TokenUsage
import io.hydrion.hub.model.isActive
import io.hydrion.hub.provider.CONFIGURED_WINDOW_PARAMETER
import io.hydrion.hub.provider.CONTEXT_WINDOW_PARAMETER
import io.hydrion.hub.provider.DEFAULT_CONTEXT_WINDOW_TOKENS
import io.hydrion.hub.provider.Provider
import io.hydrion.hub.provider.ProviderModel
import io.hydrion.hub.provider.chatProtocol
import io.hydrion.hub.provider.contextWindowTokens
import io.hydrion.hub.provider.defaultContextWindowTokens
import io.hydrion.hub.provider.externalModelId
import io.hydrion.hub.provider.knownContextWindowTokens
import io.hydrion.hub.provider.namedContextWindowTokens
import java.util.Locale

internal enum class ContextPart { Messages, Tools, Skills, System, Memory, Workspace, Search, Unattributed, Free }

internal data class ContextUsage(
    val tokens: Long?,
    val window: Int?,
    val estimated: Boolean = false,
    val usage: TokenUsage? = null
) {
    init { require(window == null || window > 0) }

    fun share(count: Long?): Double? = if (count == null || window == null) null else count.toDouble() / window
    val fraction: Float get() = (share(tokens) ?: 0.0).coerceIn(0.0, 1.0).toFloat()
    val high: Boolean get() = tokens != null && window != null && tokens >= (window * 4L + 4) / 5
    val label: String? get() = tokens?.let {
        if (estimated || it >= 1000) String.format(Locale.ROOT, "%.1fk", it / 1000.0) else it.toString()
    }

    // The demo estimate counts message rows. Provider usage includes all request content and
    // the reply; neither cached/reasoning subtotals nor local settings identify its sources.
    fun count(part: ContextPart): Long? = when (part) {
        ContextPart.Messages -> tokens.takeIf { estimated }
        ContextPart.Unattributed -> tokens.takeUnless { estimated }
        ContextPart.Free -> if (tokens != null && window != null) (window - tokens).coerceAtLeast(0) else null
        else -> null
    }
}

private fun Message?.recordedWindow(route: RemoteModel?): Int? {
    val named = namedContextWindowTokens(this?.parameters?.get("model") ?: route?.modelId.orEmpty())
    val saved = this?.parameters.orEmpty()
    return saved[CONTEXT_WINDOW_PARAMETER]?.toIntOrNull()?.takeIf {
        it > 0 && (it != DEFAULT_CONTEXT_WINDOW_TOKENS || named != null || saved[CONFIGURED_WINDOW_PARAMETER] == "true")
    } ?: named
}

internal fun contextUsage(
    conversation: Conversation, selected: Pair<ProviderModel, Provider>?, messageLimit: Int? = null,
    messages: List<Message> = conversation.messages,
): ContextUsage {
    val newestFirst = messages.asReversed()
    if (conversation.mode == Mode.Image) {
        val last = if (selected == null) newestFirst.maxByOrNull { it.createdAt } else null
        val window = selected?.first?.contextWindowTokens
            ?: last?.parameters?.get(CONTEXT_WINDOW_PARAMETER)?.toIntOrNull()?.takeIf { it > 0 }
            ?: defaultContextWindowTokens(last?.remoteModel?.modelId.orEmpty())
        return ContextUsage(0, window, estimated = true)
    }
    // A retry can replace an earlier transcript row with a newer request.
    val route = selected?.let { (model, provider) -> RemoteModel(provider.id, model.id) }
        ?: conversation.modelRoutes?.get(Mode.Chat)
        ?: newestFirst.maxByOrNull { it.createdAt }?.remoteModel
    val window = if (selected != null) selected.first.knownContextWindowTokens else
        newestFirst.asSequence().filter { it.remoteModel == route }.maxByOrNull { it.createdAt }.recordedWindow(route)
    val expectsReport = selected?.let { it.second.chatProtocol() != null } ?: (route != null)
    if (expectsReport && route != null) {
        val externalId = selected?.first?.externalModelId
        val latest = newestFirst.asSequence()
            .filter { it.role == Role.Assistant && it.remoteModel == route && !it.status.isActive }
            .filter { externalId == null || it.parameters["model"] == null || it.parameters["model"] == externalId }
            .maxByOrNull { it.createdAt }
        val usage = latest?.reportedUsage
        return ContextUsage(usage?.contextTokens, window, usage = usage)
    }
    if (selected == null && messages.isEmpty()) return ContextUsage(null, window)
    val start = conversation.contextStart?.let { id -> messages.indexOfFirst { it.id == id }.takeIf { it >= 0 } ?: messages.size } ?: 0
    val visibleCount = messages.size - start
    return ContextUsage(minOf(visibleCount, messageLimit ?: visibleCount) * 260L, window, estimated = true)
}
