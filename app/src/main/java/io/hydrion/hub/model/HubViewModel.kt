package io.hydrion.hub.model

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.hydrion.hub.provider.WebSearchStore
import io.hydrion.hub.provider.ProviderStore
import io.hydrion.hub.provider.responsesImageSources
import io.hydrion.hub.provider.LocalModelRequest
import io.hydrion.hub.provider.imageInvocation
import io.hydrion.hub.provider.prepareOfficialImageGeneration
import io.hydrion.hub.runtime.ProotEnvironmentStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal enum class WorkspaceLoad { Reading, Ready, Unavailable }

class HubViewModel(app: Application) : AndroidViewModel(app) {
    private val historyFile = HistoryFile(app)
    private val writes = Channel<Pair<Long, SavedWorkspace>>(Channel.CONFLATED)
    private val historyLock = Mutex()
    @Volatile private var storageEpoch = 0L
    private val load = MutableStateFlow(WorkspaceLoad.Reading)
    internal val workspaceLoad = load.asStateFlow()
    private val loaded get() = load.value == WorkspaceLoad.Ready
    private val initialMediaScan = CompletableDeferred<Unit>()
    private var changedDuringLoad = false
    internal val providers = ProviderStore(app)
    internal val webSearch = WebSearchStore(app)
    internal val environments by lazy { ProotEnvironmentStore(app) }
    private val images = GeneratedImageStore(app)
    private val generation = ImageGenerationRepository(
        ChatGenerationRepository({ providers.catalog.value }, providers::sessionKey, app::responsesImageSources),
        ::prepareImage, OpenAiImagesTransport()::generate, images::saveBatch, images::discard
    )
    private val mutableErrors = MutableStateFlow<Map<String, String>>(emptyMap())
    internal val errors = mutableErrors.asStateFlow()
    private val session = ConversationSession(viewModelScope, generation,
        persist = { snapshot -> if (loaded) writes.trySend(storageEpoch to snapshot) else changedDuringLoad = true },
        newRoutes = providers::newThreadRoutes, showRoutes = providers::showRoutes,
        allowSearch = { webSearch.settings.value.localEnabled }, onGenerationError = ::reportError)
    val state = session.state
    internal fun addAssistant(name: String) = loaded && session.addAssistant(name)
    internal fun renameAssistant(id: String, name: String) = loaded && session.renameAssistant(id, name)
    internal fun updateAssistant(id: String, name: String, avatar: String, prompt: String, route: RemoteModel?, contextLimit: Int? = null): Boolean {
        val old = state.value.assistants.find { it.id == id } ?: return false
        if (!loaded || (route != null && route != old.chatModel && providers.catalog.value.entries(Mode.Chat).none {
                (m, p) -> p.id == route.providerId && m.id == route.modelId
            })) return false
        return session.updateAssistant(id, name, avatar, prompt, route, contextLimit)
    }
    internal fun selectAssistant(id: String) { if (loaded) session.selectAssistant(id) }
    private val mutableNotice = MutableStateFlow<String?>(null)
    val notice = mutableNotice.asStateFlow()

    init {
        val previousRoutes = providers.legacyRoutes()
        providers.onModelPicked = session::selectModel
        providers.onAssistantModelPicked = { route ->
            state.value.assistants.find { it.id == state.value.current.assistantId }?.let {
                updateAssistant(it.id, it.name, it.avatarText, it.systemPrompt, route, it.contextLimit)
            }
        }
        providers.showRoutes(state.value.current.modelRoutes, null)
        // The single writer finishes queued atomic saves even if the Activity is closed.
        CoroutineScope(Dispatchers.IO).launch {
            for ((epoch, snapshot) in writes) {
                try {
                    historyLock.withLock { if (epoch == storageEpoch) historyFile.save(snapshot) }
                } catch (_: Exception) {
                    mutableNotice.value = "本地历史保存失败，请检查设备存储空间。"
                }
            }
        }
        viewModelScope.launch {
            try {
                val saved = withContext(Dispatchers.IO) { historyFile.load() }
                val scoped = saved.withLegacyModelRoutes(previousRoutes)
                session.restore(scoped)
                load.value = WorkspaceLoad.Ready
                if (changedDuringLoad || scoped != saved) writes.trySend(storageEpoch to session.snapshot())
                val retained = saved.imageReferences() + session.snapshot().imageReferences() +
                    state.value.draft.attachments.mapNotNull { it.imageKey }
                try {
                    images.pruneUnreferenced(retained)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    // The next startup can reclaim old images after history has been read successfully.
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                load.value = WorkspaceLoad.Unavailable
                mutableNotice.value = "本地历史加载失败，本次会话仅在内存中保留。"
            } finally { initialMediaScan.complete(Unit) }
        }
    }

    internal fun backupSnapshot(): SavedWorkspace {
        check(loaded)
        return session.snapshot(includeUnsent = true)
    }

    internal val backups by lazy { LocalChatBackup(getApplication(), images) }

    internal suspend fun applyBackup(preview: InspectedChatArchive) {
        initialMediaScan.await()
        val before = backupSnapshot()
        val legacyRoutes = providers.legacyRoutes()
        mergeBackup(before, preview.workspace.withLegacyModelRoutes(legacyRoutes)) // Fail on collisions before publishing any files.
        withContext(Dispatchers.IO) {
            var rollback: java.io.File? = null
            try {
                backups.withMedia(preview, beforeWrite = { incoming ->
                    rollback = backups.stageRollback(before, mergeBackup(before, incoming.withLegacyModelRoutes(legacyRoutes)))
                }) { incoming -> commitBackup(before, mergeBackup(before, incoming.withLegacyModelRoutes(legacyRoutes)), requireNotNull(rollback)) }
            } finally { rollback?.delete() }
        }
    }

    internal suspend fun undoBackup() {
        val (before, after) = withContext(Dispatchers.IO) { backups.rollbackPoint() }
        check(backupSnapshot() == after)
        withContext(Dispatchers.IO) { commitBackup(after, before, rollback = null) }
    }

    private suspend fun commitBackup(before: SavedWorkspace, after: SavedWorkspace, rollback: java.io.File?) {
        historyLock.withLock {
            val staged = backups.stageHistory(historyFile.path, after)
            try {
                withContext(Dispatchers.Main.immediate) {
                    check(loaded && session.snapshot(includeUnsent = true) == before)
                    rollback?.let(backups::publishRollback)
                    backups.publishHistory(staged, historyFile.path)
                    storageEpoch++
                    session.adoptBackup(after)
                }
            } finally { staged.delete() }
        }
    }

    fun clearNotice() { mutableNotice.value = null }
    internal fun reportError(id: String, error: String) { mutableErrors.update { it + (id to error) } }
    internal fun clearError(id: String) { mutableErrors.update { it - id } }
    fun newConversation() {
        if (!state.value.modeLocked) clearError(state.value.current.id)
        session.newConversation()
    }
    fun openConversation(id: String) = session.openConversation(id)
    fun setMode(mode: Mode) = session.setMode(mode)
    internal suspend fun bindEnvironment(conversationId: String, environmentId: String?) {
        if (environmentId != null) requireNotNull(environments.getEnvironment(environmentId)) { "环境已不存在。" }
        withContext(Dispatchers.Main.immediate) { session.bindEnvironment(conversationId, environmentId) }
    }
    fun rename(id: String, title: String) = session.rename(id, title)
    internal fun hasDrawerDraft(id: String) = session.hasDraft(id)
    internal fun copyAssistant(id: String, name: String) = loaded && session.copyAssistant(id, name)
    internal fun groupAssistant(id: String, group: String) = loaded && session.groupAssistant(id, group)
    internal fun canRemoveAssistant(id: String) = loaded && session.canRemoveAssistant(id)
    internal fun removeEmptyAssistant(id: String) { if (loaded) session.removeEmptyAssistant(id) }
    internal fun setPinned(ids: Set<String>, pinned: Boolean) { if (loaded) session.setPinned(ids, pinned) }
    internal fun moveConversations(ids: Set<String>, target: String) { if (loaded) session.moveConversations(ids, target) }
    internal fun duplicateConversation(id: String, title: String) { if (loaded) session.duplicateConversation(id, title) }
    internal fun deleteConversations(ids: Set<String>) {
        if (!loaded) return
        session.deleteConversations(ids)
        mutableErrors.update { it - ids }
    }
    fun togglePin(id: String) = session.togglePin(id)
    fun deleteConversation(id: String) {
        session.deleteConversation(id)
        clearError(id)
    }
    fun setDraft(text: String) = session.setDraft(text)
    internal fun restoreModelPickerDraft(id: String, draft: Draft) = session.restoreModelPickerDraft(id, draft)
    fun setSearch(value: String) = session.setSearch(value)
    fun setImageTool(value: String) = session.setImageTool(value)
    fun addAttachment(attachment: Attachment) = session.addAttachment(attachment)
    fun removeAttachment(id: String) = session.removeAttachment(id)
    fun referenceImage(imageKey: String, width: Int? = null, height: Int? = null) =
        session.referenceImage(imageKey, width, height)
    fun send(model: String, parameters: Map<String, String> = emptyMap(), search: String? = null,
             imageTool: String? = null, image: ImageInvocation? = null, remoteModel: RemoteModel? = null,
             restoreDraftOnError: Boolean = false) {
        clearError(state.value.current.id)
        session.send(model, parameters, search, imageTool, image, remoteModel, restoreDraftOnError)
    }
    fun stop() = session.stop()
    fun editMessage(id: String, text: String) = session.editMessage(id, text)
    internal fun forkMessage(id: String, title: String) = session.forkMessage(id, title)
    internal fun setContextStart(id: String?) = session.setContextStart(id)
    internal fun chooseMessageVersion(id: String, ordinal: Int) = session.chooseMessageVersion(id, ordinal)
    internal fun deleteSelectedVersions(chatId: String, ids: Set<String>, allVersions: Boolean) =
        session.deleteSelectedVersions(chatId, ids, allVersions)
    internal fun deleteChatVersion(id: String, otherVersions: Boolean = false) = session.deleteChatVersion(id, otherVersions)
    internal fun deleteChatMessage(id: String) {
        if (state.value.current.mode == Mode.Chat && !state.value.isGenerating) session.deleteMessage(id)
    }
    fun deleteMessage(id: String) = session.deleteMessage(id)
    fun regenerate(id: String) {
        clearError(state.value.current.id)
        session.regenerate(id, ::validateImage) { prompt, parentImageLimit ->
            providers.catalog.value.imageInvocation(prompt, parentImageLimit)
        }
    }
    internal fun validateImage(image: ImageInvocation, prompt: String, attachments: List<Attachment>) {
        if (image.remoteModel != null) prepareImage(image, prompt, attachments)
    }

    private fun prepareImage(image: ImageInvocation, prompt: String, attachments: List<Attachment>): LocalModelRequest {
        require(attachments.isEmpty()) { IMAGE_TEXT_ONLY }
        return providers.catalog.value.prepareOfficialImageGeneration(requireNotNull(image.remoteModel), prompt,
            image.parameters, providers::sessionKey)
    }

    fun preparePrompt(id: String) = session.preparePrompt(id)

    override fun onCleared() {
        writes.close()
        super.onCleared()
    }
}

private fun SavedWorkspace.imageReferences(): Set<String> = buildSet {
    for (conversation in history) for (message in conversation.allStoredMessages()) {
        addAll(message.imageKeys)
        message.attachments.mapNotNullTo(this) { it.imageKey }
    }
    drafts.values.forEach { draft -> draft.attachments.mapNotNullTo(this) { it.imageKey } }
}
