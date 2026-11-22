package io.hydrion.hub.model

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class EditedMessageBranchTest {
    @get:Rule val storage = TemporaryFolder()

    private val route = RemoteModel("synthetic", "model-id")
    private val owner = LocalAssistant("fixture-owner", "Fixture", systemPrompt = "Fixture style", chatModel = route)
    private fun question(text: String) = Message(role = Role.User, text = text, model = "Fixture model", remoteModel = route)
    private fun answer(question: Message, text: String) = Message(
        role = Role.Assistant, text = text, replyTo = question.id, prompt = question.text,
        model = question.model, remoteModel = question.remoteModel,
    )

    private class RecordingRepository : GenerationRepository {
        val histories = mutableListOf<List<Message>>()
        val replies = mutableListOf<Message>()
        val instructions = mutableListOf<String>()

        override fun generate(mode: Mode, request: Message, retry: Boolean, history: List<Message>, systemPrompt: String) = flow {
            check(mode == Mode.Chat)
            histories += history.toList()
            replies += request
            instructions += systemPrompt
            emit(request.copy(status = Generation.Completed, text = "Reply to " + history.last().text))
        }
    }

    @Test fun editedBranchAndOriginalUseIndependentTranscriptsAfterReopen() = runBlocking {
        val earlier = question("Before context start")
        val anchor = question("Shared context")
        val shared = answer(anchor, "Archived shared reply")
        val target = question("Original question")
        val suffix = question("Later original question")
        val source = Conversation(
            title = "Original chat", pinned = true, environmentId = "fixture-workspace",
            assistantId = owner.id, contextStart = anchor.id, modelRoutes = mapOf(Mode.Chat to route),
            messages = listOf(earlier, answer(earlier, "Earlier reply"), anchor, shared,
                target, answer(target, "Original answer"), suffix, answer(suffix, "Later original answer")),
        ).appendVersion(shared, shared.copy(text = "Selected shared reply"))
        val sourceDraft = Draft("Unsent original draft")
        val baseline = SavedWorkspace(listOf(source), mapOf(source.id to sourceDraft),
            assistants = listOf(LocalAssistant.Default, owner), selectedAssistant = owner.id)
        val disk = storage.newFile("history.json")
        disk.writeText(encodeWorkspace(baseline).toString())
        fun readHistory() = decodeWorkspace(JSONObject(disk.readText()))
        val repository = RecordingRepository()
        fun reopen() = ConversationSession(this, repository, { state ->
            disk.writeText(encodeWorkspace(state).toString())
        }).also { it.restore(readHistory()) }

        val original = reopen()
        original.openConversation(source.id)
        assertTrue(original.forkEditedMessage(source.id, target.id, "Edited question", "Edited branch"))
        yield()
        val branch = original.state.value.current
        val sharedPrefix = listOf(earlier.text, "Earlier reply", anchor.text, "Selected shared reply")
        assertEquals("Edited branch", branch.title)
        assertNotEquals(source.id, branch.id)
        assertFalse(branch.pinned)
        assertEquals(source.assistantId, branch.assistantId)
        assertEquals(source.modelRoutes, branch.modelRoutes)
        assertEquals(source.environmentId, branch.environmentId)
        assertEquals(sharedPrefix + listOf("Edited question", "Reply to Edited question"), branch.messages.map { it.text })
        assertEquals(branch.messages[2].id, branch.contextStart)
        assertTrue(branch.messages.none { next -> source.messages.any { it.id == next.id } })
        assertEquals(branch.messages[2].id, branch.messages[3].replyTo)
        assertEquals(branch.messages[4].id, branch.messages[5].replyTo)
        assertEquals(Generation.Completed, branch.messages.last().status)
        assertEquals(Draft(), original.state.value.draft)
        assertEquals(source, readHistory().history.single { it.id == source.id })
        assertEquals(sourceDraft, readHistory().drafts.getValue(source.id))
        val sharedContext = listOf(anchor.text, "Selected shared reply")
        assertEquals(sharedContext + "Edited question", repository.histories.single().map { it.text })
        assertEquals(route, repository.replies.single().remoteModel)
        assertEquals(listOf(owner.systemPrompt), repository.instructions)

        val resumed = reopen()
        resumed.openConversation(source.id)
        assertEquals(source, resumed.state.value.current)
        assertEquals(sourceDraft, resumed.state.value.draft)
        resumed.setDraft("Continue original")
        resumed.send(target.model, emptyMap(), null, null, remoteModel = route)
        yield()
        val originalHistory = source.messages.drop(2).map { it.text } + "Continue original"
        assertEquals(originalHistory, repository.histories[1].map { it.text })

        resumed.openConversation(branch.id)
        assertEquals(branch, resumed.state.value.current)
        resumed.setDraft("Continue branch")
        resumed.send(target.model, emptyMap(), null, null, remoteModel = route)
        yield()
        assertEquals(3, repository.histories.size)
        assertEquals(sharedContext + listOf("Edited question", "Reply to Edited question", "Continue branch"),
            repository.histories.last().map { it.text })

        val final = reopen()
        final.openConversation(source.id)
        assertEquals(source.messages, final.state.value.current.messages.dropLast(2))
        assertEquals(source.versions, final.state.value.current.versions)
        assertEquals("Reply to Continue original", final.state.value.current.messages.last().text)
        final.openConversation(branch.id)
        assertEquals(branch.messages, final.state.value.current.messages.dropLast(2))
        assertEquals("Reply to Continue branch", final.state.value.current.messages.last().text)
    }

    @Test fun attachmentOnlyEditBeforeContextStartUsesOriginalReplySettings() = runBlocking {
        val attachment = Attachment("fixture-file", "diagram.png", uri = "content://fixture.invalid/diagram", mimeType = "image/png")
        val target = question("Original caption").copy(remoteModel = null, attachments = listOf(attachment))
        val settings = mapOf("temperature" to "0.25", "model" to "wire-id")
        val prior = answer(target, "First answer").copy(model = "Original reply model", remoteModel = route, parameters = settings)
        val later = question("Later context start")
        val source = Conversation(messages = listOf(target, prior, later, answer(later, "Later answer")), contextStart = later.id)
        val repository = RecordingRepository()
        val session = ConversationSession(this, repository, {})
        session.restore(SavedWorkspace(listOf(source)))
        session.openConversation(source.id)

        assertTrue(session.forkEditedMessage(source.id, target.id, "", "Attachment branch"))
        yield()
        val branch = session.state.value.current
        assertNull(branch.contextStart)
        val edited = branch.messages.first()
        assertNotEquals(target.id, edited.id)
        assertEquals("", edited.text)
        assertEquals(listOf(attachment), edited.attachments)
        assertEquals(listOf(edited), repository.histories.single())
        val requested = repository.replies.single()
        assertEquals(edited.id, requested.replyTo)
        assertEquals("", requested.prompt)
        assertEquals(edited.attachments, requested.attachments)
        assertEquals(prior.model, requested.model)
        assertEquals(route, requested.remoteModel)
        assertEquals(settings, requested.parameters)
        assertEquals(Generation.Completed, branch.messages.last().status)
        assertEquals(source, session.state.value.history.single { it.id == source.id })
    }

    @Test fun imageValidationFailureStaysOnSourceWithoutCreatingOrWritingABranch() = runBlocking {
        val image = ImageInvocation("Fixture image", mapOf("n" to "1"), RemoteModel("synthetic", "image"))
        val target = question("Original image prompt").copy(imageInvocation = image)
        val prior = answer(target, "Original result").copy(imageInvocation = image)
        val source = Conversation(messages = listOf(target, prior))
        val errors = mutableListOf<Pair<String, String>>()
        val writes = mutableListOf<SavedWorkspace>()
        val repository = RecordingRepository()
        val session = ConversationSession(this, repository, { writes += it },
            onGenerationError = { id, detail -> errors += id to detail })
        session.restore(SavedWorkspace(listOf(source), drafts = mapOf(source.id to Draft("Source draft"))))
        session.openConversation(source.id)
        val before = session.state.value
        val initialWrites = writes.size
        val detail = "Synthetic image route is disabled"

        assertFalse(session.forkEditedMessage(source.id, target.id, "Edited image prompt", "Image branch",
            validateImage = { invocation, prompt, attachments ->
                assertEquals(image, invocation)
                assertEquals("Edited image prompt", prompt)
                assertTrue(attachments.isEmpty())
                throw IllegalArgumentException(detail)
            }))
        yield()
        assertEquals(listOf(source.id to detail), errors)
        assertEquals(before, session.state.value)
        assertEquals(initialWrites, writes.size)
        assertTrue(repository.histories.isEmpty())
    }

    @Test fun returningToSourceOnBranchSendingKeepsBackgroundChunksInTheBranch() {
        val scope = CoroutineScope(Dispatchers.Unconfined + Job())
        try {
            val target = question("Original prompt")
            val later = question("Remaining original turn")
            val source = Conversation(title = "Original", messages = listOf(target, answer(target, "Original reply"),
                later, answer(later, "Remaining original reply")))
            val draft = Draft("Unsent original", listOf(Attachment("fixture", "draft.txt")))
            val releasePartial = CompletableDeferred<Unit>()
            val releaseFinal = CompletableDeferred<Unit>()
            val repository = object : GenerationRepository {
                override fun generate(mode: Mode, request: Message, retry: Boolean, history: List<Message>, systemPrompt: String) = flow {
                    releasePartial.await()
                    emit(request.copy(status = Generation.Generating, text = "Partial branch answer"))
                    releaseFinal.await()
                    emit(request.copy(status = Generation.Completed, text = "Final branch answer"))
                }
            }
            val writes = mutableListOf<SavedWorkspace>()
            val session = ConversationSession(scope, repository, { writes += it })
            session.restore(SavedWorkspace(listOf(source), mapOf(source.id to draft)))
            session.openConversation(source.id)
            val returnToSource = scope.launch(start = CoroutineStart.UNDISPATCHED) {
                session.state.first { state -> state.current.id != source.id &&
                    state.current.messages.lastOrNull()?.status == Generation.Sending }
                session.openConversation(source.id)
            }

            assertTrue(session.forkEditedMessage(source.id, target.id, "New prompt", "New branch"))
            assertTrue(returnToSource.isCompleted)
            val branchId = session.state.value.history.single { it.id != source.id }.id
            fun assertSourceVisible() {
                assertEquals(source, session.state.value.current)
                assertEquals(draft, session.state.value.draft)
                assertEquals(source, session.state.value.history.single { it.id == source.id })
            }
            fun backgroundReply() = session.state.value.history.single { it.id == branchId }.messages.last()

            assertSourceVisible()
            assertEquals(Generation.Sending, backgroundReply().status)
            releasePartial.complete(Unit)
            assertSourceVisible()
            assertEquals(Generation.Generating, backgroundReply().status)
            assertEquals("Partial branch answer", backgroundReply().text)
            releaseFinal.complete(Unit)
            assertSourceVisible()
            assertEquals(Generation.Completed, backgroundReply().status)

            val reopened = ConversationSession(scope, repository, {})
            reopened.restore(decodeWorkspace(encodeWorkspace(writes.last())))
            reopened.openConversation(branchId)
            assertEquals(listOf("New prompt", "Final branch answer"), reopened.state.value.current.messages.map { it.text })
            reopened.openConversation(source.id)
            assertEquals(source, reopened.state.value.current)
            assertEquals(draft, reopened.state.value.draft)
        } finally {
            scope.cancel()
        }
    }

    @Test fun staleTargetAndInvalidEditDoNotWriteOrGenerate() = runBlocking {
        val target = question("Original")
        val reply = answer(target, "Kept answer")
        val source = Conversation(title = "Current chat", messages = listOf(target, reply))
        val repository = RecordingRepository()
        val writes = mutableListOf<SavedWorkspace>()
        val session = ConversationSession(this, repository, { writes += it })
        session.restore(SavedWorkspace(listOf(source)))
        session.openConversation(source.id)
        val original = session.state.value
        val initialWrites = writes.size

        assertFalse(session.forkEditedMessage("stale-chat", target.id, "Edit", "Branch"))
        assertFalse(session.forkEditedMessage(source.id, "missing-message", "Edit", "Branch"))
        assertFalse(session.forkEditedMessage(source.id, reply.id, "Edit", "Branch"))
        assertFalse(session.forkEditedMessage(source.id, target.id, "   ", "Branch"))
        assertFalse(session.forkEditedMessage(source.id, target.id, "Edit", "   "))
        yield()
        assertEquals(original, session.state.value)
        assertEquals(initialWrites, writes.size)
        assertTrue(repository.histories.isEmpty())
    }
}
