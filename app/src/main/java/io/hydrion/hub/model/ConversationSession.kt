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
    private val newRoutes: (RemoteModel?) -> Map<Mode, RemoteModel>? = { null },
    private val showRoutes: (Map<Mode, RemoteModel>?, RemoteModel?) -> Unit = { _, _ -> },
    private val allowSearch: () -> Boolean = { true },
    private val onGenerationError: (conversationId: String, detail: String) -> Unit = { _, _ -> }
) {
    private fun newChat(owner: String = LocalAssistant.Default.id) =
        Conversation(assistantId = owner, modelRoutes = newRoutes(assistant(owner)?.chatModel))

    private val mutable = MutableStateFlow(HubState(current = Conversation(modelRoutes = newRoutes(null))))
    val state: StateFlow<HubState> = mutable.asStateFlow()
    private val screen get() = mutable.value
    private val drafts = mutableMapOf<String, Draft>()
    // Present only while a dictated send is active and its cleared draft is still untouched.
    private val dictatedDrafts = mutableMapOf<String, Draft>()
    private val jobs = mutableMapOf<String, Job>()
    // Each assistant has at most one unsent conversation. Sent drafts remain keyed by thread ID.
    private val parked = mutableMapOf<String, Conversation>()
    private val lastThreads = mutableMapOf<String, String>()
    private val owner get() = screen.current.assistantId
    private val hasSeveral get() = screen.assistants.size > 1

    private var restoredUnsentId: String? = null

    fun restore(saved: SavedWorkspace) {
        // A fast first send can complete before disk I/O returns.
        val liveIds = screen.history.mapTo(mutableSetOf()) { it.id }
        val restored = saved.history.filterNot { it.id in liveIds }.map { item ->
            item.copy(messages = item.messages.map { message ->
                if (message.status.isActive) message.copy(status = Generation.Stopped) else message
            })
        }
        saved.drafts.forEach { (id, draft) -> drafts.putIfAbsent(id, draft) }
        val untouched = liveIds.isEmpty() && restoredUnsentId == null && !screen.modeLocked && screen.draft == Draft() &&
            screen.current.mode == Mode.Chat && screen.current.environmentId == null
        mutable.value = screen.copy(history = (screen.history + restored).sortedByDescending { it.updatedAt },
            assistants = saved.assistants)
        lastThreads.putAll(saved.lastThreads)
        saved.pending.associateByTo(parked) { it.assistantId }
        if (restoredUnsentId == null) restoredUnsentId = saved.unsent?.id
        if (untouched && (hasSeveral || saved.unsent != null || assistant(saved.selectedAssistant)?.chatModel != null)) {
            choose(rememberedLocation(saved.selectedAssistant) ?: newChat(saved.selectedAssistant))
        }
    }

    private fun rememberedLocation(assistantId: String): Conversation? =
        lastThreads[assistantId]?.let { id -> screen.history.find { it.id == id && it.assistantId == assistantId } }
            ?: parked[assistantId]

    fun snapshot(includeUnsent: Boolean = false): SavedWorkspace {
        val history = screen.history
        val savedDrafts = history.mapNotNull { item -> drafts[item.id]?.let { item.id to it } }.toMap().toMutableMap()
        val emptyThreads = parked.toMutableMap()
        if (!screen.modeLocked) emptyThreads[owner] = screen.current
        val pending = emptyThreads.values.filter { item ->
            item.environmentId != null || (hasSeveral || includeUnsent || item.id == restoredUnsentId) &&
                (item.id in drafts || item.id == restoredUnsentId || (hasSeveral && item.mode != Mode.Chat))
        }
        pending.forEach { savedDrafts[it.id] = drafts[it.id] ?: Draft() }
        val resume = if (hasSeveral || includeUnsent) history.filter { lastThreads[it.assistantId] == it.id }
            .associate { it.assistantId to it.id } else emptyMap()
        return SavedWorkspace(history, savedDrafts, pending.find { it.assistantId == owner }, screen.assistants,
            owner, pending.filter { it.assistantId != owner }.sortedBy { it.assistantId }, resume)
    }

    /** Apply a verified local import/undo while keeping an existing open thread selected. */
    fun adoptBackup(saved: SavedWorkspace) {
        val oldId = screen.current.id
        parked.clear()
        lastThreads.clear()
        drafts.clear()
        drafts.putAll(saved.drafts)
        saved.pending.associateByTo(parked) { it.assistantId }
        lastThreads.putAll(saved.lastThreads)
        restoredUnsentId = saved.unsent?.id
        mutable.value = HubState(history = saved.history, assistants = saved.assistants)
        val previous = saved.history.find { it.id == oldId }
        choose(previous ?: rememberedLocation(saved.selectedAssistant) ?: newChat(saved.selectedAssistant))
    }

    fun addAssistant(name: String, group: String? = null): Boolean {
        val clean = name.trim()
        if (!LocalAssistant.validName(clean) || (group != null && !LocalAssistant.validName(group))) return false
        mutable.value = screen.copy(assistants = screen.assistants + LocalAssistant(newId(), clean, group))
        save()
        return true
    }

    fun copyAssistant(id: String, name: String): Boolean {
        val source = assistant(id) ?: return false
        if (!LocalAssistant.validName(name.trim())) return false
        // The new ID has separate chats, notes and tool grants.
        mutable.value = screen.copy(assistants = screen.assistants + source.copy(id = newId(), name = name.trim()))
        save()
        return true
    }

    private fun assistant(id: String) = screen.assistants.find { it.id == id }

    fun updateAssistant(id: String, name: String, avatar: String, prompt: String, chatModel: RemoteModel?, contextLimit: Int? = null): Boolean {
        val previous = assistant(id) ?: return false
        if (!LocalAssistant.validName(name.trim()) || !LocalAssistant.validAvatar(avatar.trim()) || !LocalAssistant.validPrompt(prompt)) return false
        if (contextLimit != null && contextLimit !in LocalAssistant.ContextLimits) return false
        val next = previous.copy(contextLimit = contextLimit, name = name.trim(), avatarText = avatar.trim(), systemPrompt = prompt, chatModel = chatModel)
        if (next != previous) {
            mutable.value = screen.copy(assistants = screen.assistants.map { if (it.id == id) next else it })
            if (id == owner) showRoutes(screen.current.modelRoutes, chatModel)
            save()
        }
        return true
    }

    fun renameAssistant(id: String, name: String): Boolean {
        val clean = name.trim()
        if (!LocalAssistant.validName(clean) || screen.assistants.none { it.id == id }) return false
        mutable.value = screen.copy(assistants = screen.assistants.map { if (it.id == id) it.copy(name = clean) else it })
        save()
        return true
    }

    fun groupAssistant(id: String, value: String): Boolean {
        val group = value.trim().ifEmpty { null }
        if (screen.assistants.none { it.id == id } || (group != null && !LocalAssistant.validName(group))) return false
        mutable.value = screen.copy(assistants = screen.assistants.map { if (it.id == id) it.copy(sidebarGroup = group) else it })
        save()
        return true
    }

    fun canRemoveAssistant(id: String): Boolean {
        if (id == LocalAssistant.Default.id || screen.assistants.none { it.id == id }) return false
        if (screen.history.any { it.assistantId == id }) return false
        val pending = if (id == owner) screen.current else parked[id]
        return pending == null || (pending.id !in drafts && pending.id != restoredUnsentId &&
            pending.mode == Mode.Chat && pending.environmentId == null && (pending.modelRoutes.isNullOrEmpty() || pending.modelRoutes == newRoutes(assistant(id)?.chatModel)))
    }

    fun removeEmptyAssistant(id: String) {
        if (!canRemoveAssistant(id)) return
        // Going from two assistants to one must not drop the remaining assistant's saved unsent draft.
        if (screen.assistants.size == 2) {
            val remaining = if (owner != id && !screen.modeLocked) screen.current else parked[LocalAssistant.Default.id]
            if (remaining != null && (remaining.id in drafts || remaining.mode != Mode.Chat || remaining.environmentId != null)) {
                restoredUnsentId = remaining.id
            }
        }
        if (id == owner) choose(rememberedLocation(LocalAssistant.Default.id)
            ?: screen.history.firstOrNull { it.assistantId == LocalAssistant.Default.id } ?: newChat())
        parked.remove(id)
        lastThreads.remove(id)
        mutable.value = screen.copy(assistants = screen.assistants.filterNot { it.id == id })
        save()
    }

    fun selectAssistant(id: String) {
        if (owner == id || screen.assistants.none { it.id == id }) return
        rememberCurrent()
        choose(rememberedLocation(id) ?: screen.history.firstOrNull { it.assistantId == id }
            ?: newChat(id))
        save()
    }

    private fun rememberCurrent() {
        if (screen.modeLocked) {
            lastThreads[owner] = screen.current.id
        } else {
            parked[owner] = screen.current
            lastThreads.remove(owner)
        }
    }

    private fun save() = persist(snapshot())

    private fun conversation(id: String): Conversation? =
        if (screen.current.id == id) screen.current else screen.history.firstOrNull { it.id == id }

    private fun update(item: Conversation, activity: Boolean = false, restoredDraft: Draft? = null) {
        val s = screen
        val index = s.history.indexOfFirst { it.id == item.id }
        val history = when {
            index >= 0 -> s.history.toMutableList().apply { set(index, item) }
            activity -> listOf(item) + s.history
            else -> s.history
        }
        mutable.value = s.copy(current = if (s.current.id == item.id) item else s.current,
            draft = if (s.current.id == item.id && restoredDraft != null) restoredDraft else s.draft,
            history = if (activity) history.sortedByDescending { it.updatedAt } else history)
    }

    private fun touch(item: Conversation, restoredDraft: Draft? = null) {
        update(item.copy(updatedAt = now()), activity = true, restoredDraft = restoredDraft)
    }

    private fun choose(item: Conversation, history: List<Conversation> = screen.history) {
        if (parked[item.assistantId]?.id == item.id) parked.remove(item.assistantId)
        if (history.any { it.id == item.id }) lastThreads[item.assistantId] = item.id
        else lastThreads.remove(item.assistantId)
        showRoutes(item.modelRoutes, assistant(item.assistantId)?.chatModel)
        mutable.value = screen.copy(history = history, current = item, draft = drafts[item.id] ?: Draft())
    }

    fun selectModel(mode: Mode, route: RemoteModel) {
        val current = screen.current
        val routes = current.modelRoutes.orEmpty()
        if (routes[mode] == route) return
        val next = routes + (mode to route)
        if (!screen.modeLocked) restoredUnsentId = current.id
        update(current.copy(modelRoutes = next))
        showRoutes(next, assistant(owner)?.chatModel)
        save()
    }

    fun newConversation() {
        if (!screen.modeLocked) drafts.remove(screen.current.id)
        val discardImported = !screen.modeLocked && screen.current.id == restoredUnsentId
        choose(parked[owner] ?: newChat(owner))
        if (hasSeveral || discardImported) save()
    }

    fun openConversation(id: String) {
        val item = screen.history.firstOrNull { it.id == id } ?: return
        if (id == screen.current.id) return
        if (screen.modeLocked || screen.draft != Draft() || screen.current.mode != Mode.Chat ||
            screen.current.environmentId != null || item.assistantId != owner) rememberCurrent()
        choose(item)
        if (hasSeveral) save()
    }

    fun setMode(mode: Mode) {
        if (!screen.modeLocked) {
            update(screen.current.copy(mode = mode))
            if (hasSeveral) save()
        }
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

    fun hasDraft(id: String): Boolean = drafts[id]?.let { it.text.isNotBlank() || it.attachments.isNotEmpty() } == true

    fun setPinned(ids: Set<String>, pinned: Boolean) {
        val history = screen.history.map { if (it.id in ids) it.copy(pinned = pinned) else it }
        if (history == screen.history) return
        mutable.value = screen.copy(history = history, current = history.find { it.id == screen.current.id } ?: screen.current)
        save()
    }

    fun duplicateConversation(id: String, title: String) {
        val source = screen.history.find { it.id == id } ?: return
        if (source.messages.any { it.status.isActive } || title.isBlank()) return
        val copy = source.copyThrough(source.messages.lastIndex, title, now(), keepVersions = true)
        mutable.value = screen.copy(history = listOf(copy) + screen.history)
        save()
    }

    fun moveConversations(ids: Set<String>, target: String) {
        if (screen.assistants.none { it.id == target }) return
        val moving = screen.history.filter { it.id in ids && it.assistantId != target }
        if (moving.isEmpty() || moving.any { item -> item.messages.any { it.status.isActive } }) return
        val moved = moving.mapTo(mutableSetOf()) { it.id }
        val previousOwner = owner
        lastThreads.entries.removeAll { it.value in moved }
        val history = screen.history.map { if (it.id in moved) it.copy(assistantId = target) else it }
        if (screen.current.id in moved) {
            choose(history.firstOrNull { it.assistantId == previousOwner } ?: parked[previousOwner] ?: newChat(previousOwner), history)
        } else mutable.value = screen.copy(history = history)
        save()
    }

    fun deleteConversation(id: String) = deleteConversations(setOf(id))

    fun deleteConversations(ids: Set<String>) {
        if (ids.isEmpty() || (screen.current.id !in ids && screen.history.none { it.id in ids })) return
        ids.forEach { id ->
            jobs.remove(id)?.cancel()
            dictatedDrafts.remove(id)
            drafts.remove(id)
        }
        lastThreads.entries.removeAll { it.value in ids }
        parked.entries.removeAll { it.value.id in ids }
        val history = screen.history.filterNot { it.id in ids }
        if (screen.current.id in ids) choose(parked[owner] ?: newChat(owner), history)
        else mutable.value = screen.copy(history = history)
        save()
    }

    private fun changeDraft(draft: Draft) {
        if (draft == screen.draft) return
        dictatedDrafts.remove(screen.current.id)
        if (draft == Draft()) drafts.remove(screen.current.id) else drafts[screen.current.id] = draft
        mutable.value = screen.copy(draft = draft)
        if (hasSeveral || screen.modeLocked || screen.current.id == restoredUnsentId) save()
    }

    fun setDraft(text: String) = changeDraft(screen.draft.copy(text = text))

    /** Restore only the picker source and its draft, keeping live conversations and messages. */
    fun restoreModelPickerDraft(id: String, draft: Draft): Boolean {
        if (screen.current.id != id) {
            val archived = screen.history.find { it.id == id }
            if (archived != null) {
                if (archived.assistantId != owner) return false
                openConversation(id)
            } else {
                val pending = parked[owner]?.takeIf { it.id == id } ?: return false
                choose(pending)
            }
        }
        changeDraft(draft)
        return true
    }

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
             image: ImageInvocation? = null, remoteModel: RemoteModel? = null, restoreDraftOnError: Boolean = false) {
        val s = screen
        val draft = s.draft.copy(search = search ?: s.draft.search, imageTool = imageTool ?: s.draft.imageTool)
        val prompt = draft.text.trim()
        if (s.isGenerating || model.isBlank() || (prompt.isEmpty() && draft.attachments.isEmpty())) return
        val user = Message(role = Role.User, text = prompt, attachments = draft.attachments, createdAt = now(),
            model = model, remoteModel = remoteModel, parameters = parameters.toMap(),
            imageInvocation = image?.copy(parameters = image.parameters.toMap()))
        val response = Message(role = Role.Assistant, status = Generation.Sending, prompt = prompt,
            model = image?.model ?: model, parameters = user.imageInvocation?.parameters ?: user.parameters,
            replyTo = user.id, createdAt = now(), remoteModel = remoteModel, imageInvocation = user.imageInvocation,
            attachments = draft.attachments, tool = if (remoteModel == null) selectedTool(s.current.mode, draft, allowSearch()) else null)
        val title = if (!s.modeLocked && s.current.title == "新会话")
            prompt.ifBlank { draft.attachments.first().name }.lineSequence().first().take(32) else s.current.title
        changeDraft(draft.copy(text = "", attachments = emptyList()))
        if (restoreDraftOnError) dictatedDrafts[s.current.id] = s.draft
        lastThreads[s.current.assistantId] = s.current.id
        generate(s.current.copy(title = title, messages = s.current.messages + user + response), response)
    }

    private fun generate(chat: Conversation, response: Message, retry: Boolean = false) {
        val id = chat.id
        val mode = chat.mode
        val profile = assistant(chat.assistantId).takeIf { mode == Mode.Chat && response.imageInvocation == null }
        val throughPrompt = chat.messages.takeWhile { it.id != response.id }
        val context = chat.contextStart?.let { anchor -> throughPrompt.dropWhile { it.id != anchor } } ?: throughPrompt
        val history = profile?.contextLimit?.let(context::takeLast) ?: context
        val job = scope.launch(start = CoroutineStart.LAZY) {
            val generation = coroutineContext[Job]
            try {
                repository.generate(mode, response, retry, history, profile?.systemPrompt.orEmpty()).collect { message ->
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
        // An observer of Sending can stop or retry synchronously, before transport startup.
        jobs[id] = job
        touch(chat)
        save()
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
            // Release this attempt before observers can start another one for the same reply.
            jobs.remove(conversationId)
            val restoredDraft = dictatedDrafts.remove(conversationId)?.takeIf { reply.status == Generation.Error }
            if (restoredDraft != null) drafts[conversationId] = restoredDraft
            touch(updated, restoredDraft)
            save()
            if (reply.status == Generation.Error && conversation(conversationId)?.messages?.getOrNull(index) === reply)
                onGenerationError(conversationId, reply.error ?: "消息生成失败。")
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

    fun forkMessage(id: String, title: String): Boolean {
        val source = screen.current
        val end = source.messages.indexOfFirst { it.id == id }
        if (source.mode != Mode.Chat || screen.isGenerating || end < 0 || title.isBlank()) return false
        val fork = source.copyThrough(end, title, now(), keepVersions = false)
        choose(fork, listOf(fork) + screen.history)
        save()
        return true
    }

    fun setContextStart(id: String?) {
        val item = screen.current
        if (item.mode != Mode.Chat || screen.isGenerating || item.contextStart == id ||
            (id != null && item.messages.none { it.id == id })) return
        update(item.copy(contextStart = id))
        save()
    }

    fun chooseMessageVersion(id: String, ordinal: Int) {
        val item = screen.current
        if (item.mode != Mode.Chat || screen.isGenerating) return
        val next = item.withVersion(id, ordinal)
        if (next != item) { update(next); save() }
    }

    fun editMessage(id: String, text: String): Boolean {
        val item = screen.current
        val target = item.messages.find { it.id == id } ?: return false
        val cleaned = if (item.mode == Mode.Chat) text else text.trim()
        if (target.status.isActive || (item.mode == Mode.Chat && screen.isGenerating) ||
            (cleaned.isBlank() && (target.attachments.isEmpty() || (item.mode == Mode.Chat && target.role != Role.User)))) return false
        if (cleaned == target.text) return true
        val edited = target.copy(text = cleaned)
        if (item.mode == Mode.Chat) {
            val version = if (target.role == Role.Assistant) edited.copy(thought = "", tool = null, totalTokens = null,
                usage = null, status = Generation.Completed, error = null) else edited
            touch(item.appendVersion(target, version))
        } else touch(item.copy(messages = item.messages.map { if (it.id == id) edited else it }))
        save()
        return true
    }

    fun deleteSelectedVersions(conversationId: String, ids: Set<String>, allVersions: Boolean) {
        val item = screen.current
        if (item.id != conversationId) return
        val next = item.deleteSelection(ids, allVersions)
        if (next !== item) { touch(next); save() }
    }

    fun deleteChatVersion(id: String, otherVersions: Boolean = false, expectedGroup: MessageVersions? = null) {
        val item = screen.current
        if (item.mode != Mode.Chat || screen.isGenerating) return
        val group = item.versions[id]
        if (otherVersions) {
            if (group == null || (expectedGroup != null && group != expectedGroup)) return
            touch(item.copy(versions = item.versions - id))
        } else if (group != null) {
            touch(item.withVersion(id, group.selected.coerceAtMost(group.others.lastIndex), deleteCurrent = true))
        } else {
            deleteMessage(id)
            return
        }
        save()
    }

    fun deleteMessage(id: String) {
        val item = screen.current
        val removed = item.messages.filter { it.id == id || it.replyTo == id }
        if (removed.isEmpty() || (item.mode == Mode.Chat && removed.any { it.id == item.contextStart })) return
        if (removed.any { it.status.isActive }) {
            jobs.remove(item.id)?.cancel()
            dictatedDrafts.remove(item.id)
        }
        touch(item.copy(messages = item.messages.filterNot { it.id == id || it.replyTo == id }, versions = item.versions - removed.map { it.id }.toSet()))
        save()
    }

    fun regenerate(id: String, validateImage: (ImageInvocation, String, List<Attachment>) -> Unit = { _, _, _ -> },
                   resolveImage: (String, Int?) -> ImageInvocation = { _, _ ->
        throw IllegalArgumentException(IMAGE_MODEL_REQUIRED)
    }) {
        val item = screen.current
        if (screen.isGenerating) return
        val target = item.messages.find { it.id == id } ?: return
        if (item.mode == Mode.Chat && !item.canRetryChatMessage(target)) return
        val user = if (target.role == Role.User) target else item.messages.find { it.id == target.replyTo } ?: return
        val original = item.messages.find { it.replyTo == user.id }
        val needsImage = item.mode == Mode.Image || user.imageInvocation != null ||
            original?.tool?.contains("图片生成") == true || !original?.imageKeys.isNullOrEmpty()
        val image = if (needsImage) {
            val parentLimit = if (item.mode == Mode.Chat) user.parameters["image_budget"]?.toIntOrNull() else null
            try {
                val previous = original?.imageInvocation ?: user.imageInvocation
                val invocation = previous?.takeIf { it.remoteModel != null } ?: resolveImage(user.text, parentLimit)
                validateImage(invocation, user.text, user.attachments)
                invocation
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
            prompt = user.text, attachments = user.attachments, replyTo = user.id, imageInvocation = image,
            imageKeys = if (original != null && image?.remoteModel != null && original.imageInvocation == image) original.imageKeys else emptyList(),
            tool = if (image != null && item.mode == Mode.Chat) "图片生成 · $CUSTOM_IMAGE_TOOL" else original?.tool?.takeUnless {
                !allowSearch() && it.startsWith("网络搜索 · ")
            },
            createdAt = now())
        val next = if (item.mode == Mode.Chat && original != null) {
            item.appendVersion(original, response)
        } else {
            val messages = item.messages.filterNot { it.id == response.id }.toMutableList()
            messages.add(messages.indexOfFirst { it.id == user.id } + 1, response)
            item.copy(messages = messages)
        }
        generate(next, response, retry = true)
    }
}
