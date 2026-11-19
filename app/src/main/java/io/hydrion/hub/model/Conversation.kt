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

data class ImageInvocation(val model: String, val parameters: Map<String, String>)

data class Draft(
    val text: String = "",
    val attachments: List<Attachment> = emptyList(),
    val search: String = "模型内置搜索",
    val imageTool: String = "自定义图片生成"
)

data class RemoteModel(val providerId: String, val modelId: String)

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
    val totalTokens: Long? = null
)

data class Conversation(
    val id: String = newId(),
    val title: String = "新会话",
    val mode: Mode = Mode.Chat,
    val pinned: Boolean = false,
    val updatedAt: Long = System.currentTimeMillis(),
    val messages: List<Message> = emptyList()
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
