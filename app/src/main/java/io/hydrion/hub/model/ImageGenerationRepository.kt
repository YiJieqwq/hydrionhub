package io.hydrion.hub.model

import io.hydrion.hub.provider.LocalModelRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext

internal const val IMAGE_TEXT_ONLY = "当前图像生成接口仅支持文字提示，请移除参考图或附件。"

/** Both image mode and the local chat image action carry the same explicit image invocation. */
internal class ImageGenerationRepository(
    private val other: GenerationRepository,
    private val prepare: (ImageInvocation, String, List<Attachment>) -> LocalModelRequest,
    private val generateImages: suspend (LocalModelRequest) -> List<ByteArray>,
    private val saveBatch: suspend (List<ByteArray>) -> List<String>,
    private val discard: (List<String>) -> Unit
) : GenerationRepository {
    override fun chatWithQuestions(request: Message, retry: Boolean, history: List<Message>, systemPrompt: String, turn: AskUserTurn) =
        other.chatWithQuestions(request, retry, history, systemPrompt, turn)

    override fun generate(mode: Mode, request: Message, retry: Boolean, history: List<Message>, systemPrompt: String): Flow<Message> {
        val image = request.imageInvocation
        if (image?.remoteModel == null) return other.generate(mode, request, retry, history, systemPrompt)
        return flow {
            require(request.remoteModel == null && (mode == Mode.Image || request.tool == "图片生成 · $CUSTOM_IMAGE_TOOL")) {
                "请通过图像模式或本机聊天的图片生成功能发送。"
            }
            require(request.attachments.isEmpty()) { IMAGE_TEXT_ONLY }
            val prepared = prepare(image, request.prompt, request.attachments)
            emit(request.copy(status = Generation.Generating))
            val images = generateImages(prepared)
            val count = prepared.body.getInt("n")
            require(images.isNotEmpty() && images.size == count) { "服务器返回的图片数量与请求不符。" }

            val keys = saveBatch(images)
            var delivered = false
            try {
                check(keys.size == count) { "生成图片保存不完整。" }
                emit(request.copy(status = Generation.Completed, imageKeys = keys,
                    text = if (mode == Mode.Chat) "已生成 ${keys.size} 张图片。" else ""))
                delivered = true
            } finally {
                if (!delivered) withContext(NonCancellable + Dispatchers.IO) {
                    try {
                        discard(keys)
                    } catch (_: java.io.IOException) {
                        // Startup pruning can retry this batch while preserving the original cancellation or failure.
                    }
                }
            }
        }
    }
}
