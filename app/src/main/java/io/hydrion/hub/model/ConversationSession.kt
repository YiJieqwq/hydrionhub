package io.hydrion.hub.model

import io.hydrion.hub.provider.IMAGE_MODEL_REQUIRED
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/** User events and generation emissions are serialized by the ViewModel's main scope. */
internal class ConversationSession(
    private val scope: CoroutineScope,
    private val repository: GenerationRepository,
    private val persist: (SavedWorkspace) -> Unit,
    private val now: () -> Long = System::currentTimeMillis,
    private val onGenerationError: (conversationId: String, detail: String) -> Unit = { _, _ -> }
) {
    private val mutable = MutableStateFlow(HubState())
    val state: StateFlow<HubState> = mutable.asStateFlow()
    private val screen get() = mutable.value
    private val drafts = mutableMapOf<String, Draft>()
    private val jobs = mutableMapOf<String, Job>()
    private var parkedEmpty: Conversation? = null

    fun restore(saved: SavedWorkspace) {
        // A fast first send can complete before disk I/O returns.
        val liveIds = screen.history.mapTo(mutableSetOf()) { it.id }
        val restored = saved.history.filterNot { it.id in liveIds }.map { item ->
            item.copy(messages = item.messages.map { message ->
                if (message.status.isActive) message.copy(status = Generation.Stopped) else message
            })
        }
        saved.drafts.forEach { (id, draft) -> drafts.putIfAbsent(id, draft) }
        mutable.value = screen.copy(history = (screen.history + restored).sortedByDescending { it.updatedAt })
    }

    fun snapshot(): SavedWorkspace {
        val history = screen.history
        val savedDrafts = history.mapNotNull { item -> drafts[item.id]?.let { item.id to it } }.toMap()
        return SavedWorkspace(history, savedDrafts)
    }

    private fun save() = persist(snapshot())

    private fun conversation(id: String): Conversation? =
        if (screen.current.id == id) screen.current else screen.history.firstOrNull { it.id == id }

    private fun update(item: Conversation, activity: Boolean = false) {
        val s = screen
        val index = s.history.indexOfFirst { it.id == item.id }
        val history = when {
            index >= 0 -> s.history.toMutableList().apply { set(index, item) }
            activity -> listOf(item) + s.history
            else -> s.history
        }
        mutable.value = s.copy(current = if (s.current.id == item.id) item else s.current,
            history = if (activity) history.sortedByDescending { it.updatedAt } else history)
    }

    private fun touch(item: Conversation) {
        update(item.copy(updatedAt = now()), activity = true)
    }

    private fun choose(item: Conversation) {
        mutable.value = screen.copy(current = item, draft = drafts[item.id] ?: Draft())
    }

    fun newConversation() {
        if (!screen.modeLocked) drafts.remove(screen.current.id)
        val pending = parkedEmpty
        parkedEmpty = null
        choose(pending ?: Conversation())
    }

    fun openConversation(id: String) {
        val item = screen.history.firstOrNull { it.id == id } ?: return
        if (!screen.modeLocked && (screen.draft != Draft() || screen.current.mode != Mode.Chat || screen.current.environmentId != null))
            parkedEmpty = screen.current
        choose(item)
    }

    fun setMode(mode: Mode) {
        if (!screen.modeLocked) update(screen.current.copy(mode = mode))
    }

    fun bindEnvironment(conversationId: String, environmentId: String?) {
        val item = requireNotNull(conversation(conversationId)) { "会话已不存在。" }
        if (item.environmentId == environmentId) return
        update(item.copy(environmentId = environmentId))
        save()
    }

    fun rename(id: String, title: String) {
        val cleaned = title.trim()
        if (cleaned.isEmpty()) return
        val item = conversation(id) ?: return
        update(item.copy(title = cleaned))
        save()
    }

    fun togglePin(id: String) {
        val item = screen.history.firstOrNull { it.id == id } ?: return
        update(item.copy(pinned = !item.pinned))
        save()
    }

    fun deleteConversation(id: String) {
        if (conversation(id) == null) return
        jobs.remove(id)?.cancel()
        drafts.remove(id)
        mutable.value = screen.copy(history = screen.history.filterNot { it.id == id })
        if (screen.current.id == id) {
            val next = parkedEmpty ?: Conversation()
            parkedEmpty = null
            choose(next)
        }
        save()
    }

    private fun changeDraft(draft: Draft) {
        if (draft == screen.draft) return
        if (draft == Draft()) drafts.remove(screen.current.id) else drafts[screen.current.id] = draft
        mutable.value = screen.copy(draft = draft)
        if (screen.modeLocked) save()
    }

    fun setDraft(text: String) = changeDraft(screen.draft.copy(text = text))
    fun setSearch(value: String) = changeDraft(screen.draft.copy(search = value))
    fun setImageTool(value: String) = changeDraft(screen.draft.copy(imageTool = value))
    fun addAttachment(attachment: Attachment) {
        if (screen.draft.attachments.none { it.id == attachment.id })
            changeDraft(screen.draft.copy(attachments = screen.draft.attachments + attachment))
    }
    fun removeAttachment(id: String) = changeDraft(screen.draft.copy(attachments = screen.draft.attachments.filterNot { it.id == id }))
    fun referenceImage(key: String, width: Int? = null, height: Int? = null) {
        if (screen.draft.attachments.none { it.imageKey == key })
            addAttachment(Attachment(newId(), "参考图", imageKey = key, mimeType = "image/png",
                width = width, height = height))
    }

    fun send(model: String, parameters: Map<String, String>, search: String?, imageTool: String?,
             image: ImageInvocation? = null, remoteModel: RemoteModel? = null) {
        val s = screen
        val draft = s.draft.copy(search = search ?: s.draft.search, imageTool = imageTool ?: s.draft.imageTool)
        val prompt = draft.text.trim()
        if (s.isGenerating || model.isBlank() || (prompt.isEmpty() && draft.attachments.isEmpty())) return
        val user = Message(role = Role.User, text = prompt, attachments = draft.attachments, createdAt = now(),
            model = model, remoteModel = remoteModel, parameters = parameters.toMap(),
            imageInvocation = image?.copy(parameters = image.parameters.toMap()))
        val response = Message(role = Role.Assistant, status = Generation.Sending, prompt = prompt,
            model = image?.model ?: model, parameters = user.imageInvocation?.parameters ?: user.parameters,
            replyTo = user.id, createdAt = now(), remoteModel = remoteModel,
            attachments = draft.attachments, tool = if (remoteModel == null) selectedTool(s.current.mode, draft) else null)
        val title = if (!s.modeLocked && s.current.title == "新会话")
            prompt.ifBlank { draft.attachments.first().name }.lineSequence().first().take(32) else s.current.title
        changeDraft(draft.copy(text = "", attachments = emptyList()))
        touch(s.current.copy(title = title, messages = s.current.messages + user + response))
        generate(s.current.id, s.current.mode, response)
    }

    private fun generate(id: String, mode: Mode, response: Message, retry: Boolean = false) {
        val history = requireNotNull(conversation(id)).messages.takeWhile { it.id != response.id }
        save()
        val job = scope.launch(start = CoroutineStart.LAZY) {
            val generation = coroutineContext[Job]
            try {
                repository.generate(mode, response, retry, history).collect { message ->
                    if (jobs[id] === generation) replaceReply(id, response.id, message)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                if (jobs[id] !== generation) return@launch
                val partial = conversation(id)?.messages?.find { it.id == response.id } ?: return@launch
                val detail = failure.localizedMessage?.takeIf { it.isNotBlank() } ?: "生成中断，请重新生成。"
                replaceReply(id, response.id, partial.copy(status = Generation.Error, error = detail))
            }
        }
        jobs[id] = job
        job.start()
    }

    private fun replaceReply(conversationId: String, messageId: String, reply: Message) {
        val item = conversation(conversationId) ?: return
        val index = item.messages.indexOfFirst { it.id == messageId && it.status.isActive }
        if (index < 0) return
        val updated = item.copy(messages = item.messages.toMutableList().apply { set(index, reply) })
        if (reply.status.isActive) {
            update(updated)
        } else {
            touch(updated)
            jobs.remove(conversationId)
            save()
            if (reply.status == Generation.Error) onGenerationError(conversationId, reply.error ?: "消息生成失败。")
        }
    }

    fun stop() {
        val item = screen.current
        val response = item.messages.find { it.status.isActive } ?: return
        jobs.remove(item.id)?.cancel()
        replaceReply(item.id, response.id, response.copy(status = Generation.Stopped))
    }

    fun preparePrompt(id: String) {
        val message = screen.current.messages.find { it.id == id } ?: return
        val text = if (message.role == Role.User) message.text else message.prompt
        changeDraft(screen.draft.copy(text = text, attachments = message.attachments))
    }

    fun editMessage(id: String, text: String) {
        val item = screen.current
        val target = item.messages.find { it.id == id } ?: return
        if (target.status.isActive || (text.isBlank() && target.attachments.isEmpty())) return
        touch(item.copy(messages = item.messages.map { if (it.id == id) it.copy(text = text.trim()) else it }))
        save()
    }

    fun deleteMessage(id: String) {
        val item = screen.current
        val removed = item.messages.filter { it.id == id || it.replyTo == id }
        if (removed.isEmpty()) return
        if (removed.any { it.status.isActive }) jobs.remove(item.id)?.cancel()
        touch(item.copy(messages = item.messages.filterNot { it.id == id || it.replyTo == id }))
        save()
    }

    fun regenerate(id: String, resolveImage: (String, Int?) -> ImageInvocation = { _, _ ->
        throw IllegalArgumentException(IMAGE_MODEL_REQUIRED)
    }) {
        val item = screen.current
        if (screen.isGenerating) return
        val target = item.messages.find { it.id == id } ?: return
        val user = if (target.role == Role.User) target else item.messages.find { it.id == target.replyTo } ?: return
        val original = item.messages.find { it.replyTo == user.id }
        val needsImage = item.mode == Mode.Image || user.imageInvocation != null ||
            original?.tool?.contains("图片生成") == true || !original?.imageKeys.isNullOrEmpty()
        val image = if (needsImage) {
            val parentLimit = if (item.mode == Mode.Chat) user.parameters["image_budget"]?.toIntOrNull() else null
            try {
                resolveImage(user.text, parentLimit)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                onGenerationError(item.id, failure.localizedMessage?.takeIf { it.isNotBlank() } ?: "请检查图像生成模型的配置。")
                return
            }
        } else null
        val response = Message(id = original?.id ?: newId(), role = Role.Assistant, status = Generation.Sending,
            remoteModel = if (image == null) original?.remoteModel ?: user.remoteModel else null,
            model = image?.model ?: original?.model ?: user.model,
            parameters = image?.parameters ?: original?.parameters ?: user.parameters,
            prompt = user.text, attachments = user.attachments, replyTo = user.id,
            tool = if (image != null && item.mode == Mode.Chat) "图片生成 · $CUSTOM_IMAGE_TOOL" else original?.tool,
            createdAt = now())
        val messages = item.messages.filterNot { it.id == response.id }.toMutableList()
        messages.add(messages.indexOfFirst { it.id == user.id } + 1, response)
        touch(item.copy(messages = messages))
        generate(item.id, item.mode, response, retry = true)
    }
}
