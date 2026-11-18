package io.hydrion.hub.model

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class HubViewModel(app: Application) : AndroidViewModel(app) {
    private val historyFile = HistoryFile(app)
    private val writes = Channel<SavedWorkspace>(Channel.CONFLATED)
    private var loaded = false
    private var changedDuringLoad = false
    private val session = ConversationSession(viewModelScope, FakeGenerationRepository(), { snapshot ->
        if (loaded) writes.trySend(snapshot) else changedDuringLoad = true
    })
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
    fun newConversation() = session.newConversation()
    fun openConversation(id: String) = session.openConversation(id)
    fun setMode(mode: Mode) = session.setMode(mode)
    fun rename(id: String, title: String) = session.rename(id, title)
    fun togglePin(id: String) = session.togglePin(id)
    fun deleteConversation(id: String) = session.deleteConversation(id)
    fun setDraft(text: String) = session.setDraft(text)
    fun setSearch(value: String) = session.setSearch(value)
    fun setImageTool(value: String) = session.setImageTool(value)
    fun addAttachment(attachment: Attachment) = session.addAttachment(attachment)
    fun removeAttachment(id: String) = session.removeAttachment(id)
    fun referenceImage(imageKey: String, width: Int? = null, height: Int? = null) =
        session.referenceImage(imageKey, width, height)
    fun send(model: String, parameters: Map<String, String> = emptyMap(), search: String? = null,
             imageTool: String? = null, image: ImageInvocation? = null) =
        session.send(model, parameters, search, imageTool, image)
    fun stop() = session.stop()
    fun editMessage(id: String, text: String) = session.editMessage(id, text)
    fun deleteMessage(id: String) = session.deleteMessage(id)
    fun regenerate(id: String) = session.regenerate(id)
    fun preparePrompt(id: String) = session.preparePrompt(id)

    override fun onCleared() {
        writes.close()
        super.onCleared()
    }
}
