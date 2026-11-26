package io.hydrion.hub.model

import java.util.UUID

enum class Mode { Chat, Image }
enum class Role { User, Assistant }
enum class Generation { Sending, Generating, Completed, Stopped, Error }

internal val Generation.isActive: Boolean
    get() = this == Generation.Sending || this == Generation.Generating

internal fun newId(): String = UUID.randomUUID().toString()

data class Attachment(
    val id: String,
    val name: String,
    val uri: String? = null,
    val imageKey: String? = null,
    val mimeType: String? = null,
    val width: Int? = null,
    val height: Int? = null
)

data class ImageInvocation(
    val model: String,
    val parameters: Map<String, String>,
    val remoteModel: RemoteModel? = null
)

internal const val CUSTOM_IMAGE_TOOL = "自定义图片生成"

data class Draft(
    val text: String = "",
    val attachments: List<Attachment> = emptyList(),
    val search: String = "模型内置搜索",
    val imageTool: String = CUSTOM_IMAGE_TOOL
)

data class RemoteModel(val providerId: String, val modelId: String)

/** Counts reported for a single response. Cached and reasoning tokens are subsets of input and output. */
data class TokenUsage(
    val inputTokens: Long? = null,
    val outputTokens: Long? = null,
    val totalTokens: Long? = null,
    val cachedInputTokens: Long? = null,
    val reasoningTokens: Long? = null
) {
    val contextTokens: Long? get() {
        totalTokens?.takeIf { it >= 0 }?.let { return it }
        val input = inputTokens ?: return null
        val output = outputTokens ?: return null
        return if (input >= 0 && output >= 0 && input <= Long.MAX_VALUE - output) input + output else null
    }
}

data class Message(
    val id: String = newId(),
    val role: Role,
    val text: String = "",
    val status: Generation = Generation.Completed,
    val createdAt: Long = System.currentTimeMillis(),
    val model: String = "",
    val parameters: Map<String, String> = emptyMap(),
    val attachments: List<Attachment> = emptyList(),
    val prompt: String = "",
    val thought: String = "",
    val tool: String? = null,
    val imageKeys: List<String> = emptyList(),
    val error: String? = null,
    val replyTo: String? = null,
    val imageInvocation: ImageInvocation? = null,
    val remoteModel: RemoteModel? = null,
    val totalTokens: Long? = null,
    val usage: TokenUsage? = null,
    val timing: ReplyTiming? = null,
    val toolCalls: List<ReplyToolCall> = emptyList(),
    val translation: ReplyTranslation? = null,
    // A separate reply following an edited assistant turn. replyTo still identifies its user prompt.
    val continuesAssistant: Boolean = false,
    val replySteps: List<ReplyStep> = emptyList(),
) {
    val reportedUsage: TokenUsage? get() = usage ?: totalTokens?.let { TokenUsage(totalTokens = it) }
}

/** Assistant-scoped panel choices. Only AskUser can be offered to a model, and its answer requires submission. */
enum class ManualPanelTool { Time, CreateEvent, ViewCalendar, Browser, AskUser }

data class LocalAssistant(
    val id: String, val name: String, val sidebarGroup: String? = null,
    val systemPrompt: String = "", val chatModel: RemoteModel? = null, val avatarText: String = "",
    val contextLimit: Int? = null,
    val manualPanelTools: Set<ManualPanelTool> = emptySet(),
) {
    companion object {
        val Default = LocalAssistant("default", "HydrionHub")
        val ContextLimits = 1..4096
        const val PromptLimit = 16_384
        fun validPrompt(value: String) = value.length <= PromptLimit && value.none { it.isISOControl() && it !in "\r\n\t" }
        fun validAvatar(value: String) = value.codePointCount(0, value.length) <= 8 && value.none(Char::isISOControl)
        fun validName(name: String) = name.isNotBlank() && name.length <= 64 && name.none(Char::isISOControl)
    }
}

data class Conversation(
    val id: String = newId(),
    val title: String = "新会话",
    val mode: Mode = Mode.Chat,
    val pinned: Boolean = false,
    val updatedAt: Long = System.currentTimeMillis(),
    val messages: List<Message> = emptyList(),
    val environmentId: String? = null,
    val assistantId: String = LocalAssistant.Default.id,
    val modelRoutes: Map<Mode, RemoteModel>? = null,
    val contextStart: String? = null,
    val versions: Map<String, MessageVersions> = emptyMap(),
    val archived: Boolean = false,
    val panelActionsDenied: Boolean = false,
) {
    internal val hasToolPanelSettings get() = environmentId != null || panelActionsDenied
}

data class HubState(
    val current: Conversation = Conversation(),
    val history: List<Conversation> = emptyList(),
    val draft: Draft = Draft(),
    val assistants: List<LocalAssistant> = listOf(LocalAssistant.Default)
) {
    // History membership survives deleting the last message and makes the mode lock durable.
    val modeLocked: Boolean get() = history.any { it.id == current.id }
    val isGenerating: Boolean get() = current.messages.any { it.status.isActive }
}

internal data class SavedWorkspace(
    val history: List<Conversation> = emptyList(),
    val drafts: Map<String, Draft> = emptyMap(),
    val unsent: Conversation? = null,
    val assistants: List<LocalAssistant> = listOf(LocalAssistant.Default),
    val selectedAssistant: String = LocalAssistant.Default.id,
    val otherUnsent: List<Conversation> = emptyList(),
    val lastThreads: Map<String, String> = emptyMap(),
) {
    val pending: List<Conversation> get() = listOfNotNull(unsent) + otherUnsent
}

/** Older histories used one global selection. Snapshot it once before a new default can change it. */
internal fun SavedWorkspace.withLegacyModelRoutes(routes: Map<Mode, RemoteModel>): SavedWorkspace {
    fun pin(chat: Conversation) = if (chat.modelRoutes == null) chat.copy(modelRoutes = routes) else chat
    return copy(history = history.map(::pin), unsent = unsent?.let(::pin), otherUnsent = otherUnsent.map(::pin))
}
