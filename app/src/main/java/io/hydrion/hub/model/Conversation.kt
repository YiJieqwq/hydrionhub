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
    val usage: TokenUsage? = null
) {
    val reportedUsage: TokenUsage? get() = usage ?: totalTokens?.let { TokenUsage(totalTokens = it) }
}

data class Conversation(
    val id: String = newId(),
    val title: String = "新会话",
    val mode: Mode = Mode.Chat,
    val pinned: Boolean = false,
    val updatedAt: Long = System.currentTimeMillis(),
    val messages: List<Message> = emptyList(),
    val environmentId: String? = null
)

data class HubState(
    val current: Conversation = Conversation(),
    val history: List<Conversation> = emptyList(),
    val draft: Draft = Draft()
) {
    // History membership survives deleting the last message and makes the mode lock durable.
    val modeLocked: Boolean get() = history.any { it.id == current.id }
    val isGenerating: Boolean get() = current.messages.any { it.status.isActive }
}

internal data class SavedWorkspace(
    val history: List<Conversation> = emptyList(),
    val drafts: Map<String, Draft> = emptyMap()
)
