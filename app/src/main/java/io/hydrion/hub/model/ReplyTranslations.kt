package io.hydrion.hub.model

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

internal data class TranslationProgress(
    val chatId: String, val source: Message, val language: String, val text: String = "", val failed: Boolean = false
)

/** Main-scope jobs tied to the visible revision. A translation never becomes a transcript turn. */
internal class ReplyTranslations(
    private val scope: CoroutineScope,
    private val session: ConversationSession,
    private val repository: GenerationRepository,
) {
    private val mutable = MutableStateFlow(emptyMap<String, TranslationProgress>())
    val progress = mutable.asStateFlow()
    private val jobs = mutableMapOf<String, Job>()

    fun start(chatId: String, source: Message, language: String, route: RemoteModel) {
        val current = session.state.value.current
        val target = language.trim()
        if (current.id != chatId || current.messages.none { it === source } || source.role != Role.Assistant ||
            source.status.isActive || source.text.isBlank() || target.isEmpty() || target.length > 80 ||
            target.any(Char::isISOControl)) return
        val id = source.id
        val previous = mutable.value[id]
        if (jobs[id]?.isActive == true && previous?.source === source && previous.language == target) return
        cancel(id)
        val initial = TranslationProgress(chatId, source, target)
        mutable.value += id to initial
        val job = scope.launch(start = CoroutineStart.LAZY) {
            val owner = coroutineContext[Job]
            try {
                val prompt = Message(role = Role.User, text = source.text)
                val request = Message(role = Role.Assistant, replyTo = prompt.id, remoteModel = route)
                var completed: String? = null
                repository.generate(Mode.Chat, request, retry = false, history = listOf(prompt),
                    systemPrompt = "Translate the user message into $target. Return only the translation. Preserve Markdown formatting.").collect { reply ->
                    if (jobs[id] === owner) {
                        mutable.value += id to initial.copy(text = reply.text)
                        if (reply.status == Generation.Completed) completed = reply.text
                    }
                }
                if (jobs[id] === owner) {
                    val result = completed?.takeIf(String::isNotBlank)
                    if (result == null) mutable.value += id to initial.copy(failed = true)
                    else {
                        session.setTranslation(chatId, source, ReplyTranslation(target, result))
                        mutable.value -= id
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (jobs[id] === owner) mutable.value += id to initial.copy(failed = true)
            } finally {
                if (jobs[id] === owner) jobs.remove(id)
            }
        }
        jobs[id] = job
        job.start()
    }

    fun cancel(id: String) {
        jobs.remove(id)?.cancel()
        mutable.value -= id
    }

    fun clear(chatId: String, source: Message) {
        cancel(source.id)
        session.setTranslation(chatId, source, null)
    }

    fun retain(chatId: String, messages: List<Message>) {
        for ((id, run) in mutable.value) {
            if (run.chatId == chatId && messages.none { it === run.source }) cancel(id)
        }
    }
}
