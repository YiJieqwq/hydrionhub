package io.hydrion.hub.model

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.hydrion.hub.provider.ProviderStore
import io.hydrion.hub.provider.responsesImageSources
import io.hydrion.hub.provider.imageRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class HubViewModel(app: Application) : AndroidViewModel(app) {
    private val historyFile = HistoryFile(app)
    private val writes = Channel<SavedWorkspace>(Channel.CONFLATED)
    private var loaded = false
    private var changedDuringLoad = false
    internal val providers = ProviderStore(app)
    private val generation = ChatGenerationRepository({ providers.catalog.value }, providers::sessionKey,
        app.contentResolver::responsesImageSources)
    private val mutableErrors = MutableStateFlow<Map<String, String>>(emptyMap())
    internal val errors = mutableErrors.asStateFlow()
    private val session = ConversationSession(viewModelScope, generation,
        persist = { snapshot -> if (loaded) writes.trySend(snapshot) else changedDuringLoad = true },
        onGenerationError = ::reportError)
    val state = session.state
    private val mutableNotice = MutableStateFlow<String?>(null)
    val notice = mutableNotice.asStateFlow()

    init {
        // The single writer finishes queued atomic saves even if the Activity is closed.
        CoroutineScope(Dispatchers.IO).launch {
            for (snapshot in writes) {
                try {
                    historyFile.save(snapshot)
                } catch (_: Exception) {
                    mutableNotice.value = "本地历史保存失败，请检查设备存储空间。"
                }
            }
        }
        viewModelScope.launch {
            try {
                val saved = withContext(Dispatchers.IO) { historyFile.load() }
                session.restore(saved)
                loaded = true
                if (changedDuringLoad) writes.trySend(session.snapshot())
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutableNotice.value = "本地历史加载失败，本次会话仅在内存中保留。"
            }
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
    fun rename(id: String, title: String) = session.rename(id, title)
    fun togglePin(id: String) = session.togglePin(id)
    fun deleteConversation(id: String) {
        session.deleteConversation(id)
        clearError(id)
    }
    fun setDraft(text: String) = session.setDraft(text)
    fun setSearch(value: String) = session.setSearch(value)
    fun setImageTool(value: String) = session.setImageTool(value)
    fun addAttachment(attachment: Attachment) = session.addAttachment(attachment)
    fun removeAttachment(id: String) = session.removeAttachment(id)
    fun referenceImage(imageKey: String, width: Int? = null, height: Int? = null) =
        session.referenceImage(imageKey, width, height)
    fun send(model: String, parameters: Map<String, String> = emptyMap(), search: String? = null,
             imageTool: String? = null, image: ImageInvocation? = null, remoteModel: RemoteModel? = null) {
        clearError(state.value.current.id)
        session.send(model, parameters, search, imageTool, image, remoteModel)
    }
    fun stop() = session.stop()
    fun editMessage(id: String, text: String) = session.editMessage(id, text)
    fun deleteMessage(id: String) = session.deleteMessage(id)
    fun regenerate(id: String) {
        clearError(state.value.current.id)
        session.regenerate(id) { prompt, parentImageLimit ->
            val request = providers.catalog.value.imageRequest(prompt, parentImageLimit)
            ImageInvocation(request.model, request.parameters)
        }
    }
    fun preparePrompt(id: String) = session.preparePrompt(id)

    override fun onCleared() {
        writes.close()
        super.onCleared()
    }
}
