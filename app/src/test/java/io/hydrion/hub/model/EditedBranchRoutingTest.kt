package io.hydrion.hub.model

import io.hydrion.hub.provider.Capability
import io.hydrion.hub.provider.ChatProtocol
import io.hydrion.hub.provider.LocalModelRequest
import io.hydrion.hub.provider.ModelCatalog
import io.hydrion.hub.provider.ModelProfile
import io.hydrion.hub.provider.Provider
import io.hydrion.hub.provider.ProviderCatalogStorage
import io.hydrion.hub.provider.ProviderModel
import io.hydrion.hub.provider.ProviderStore
import io.hydrion.hub.provider.newProvider
import io.hydrion.hub.provider.route
import io.hydrion.hub.ui.sendDraft
import io.hydrion.hub.ui.sendEditedBranch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class EditedBranchRoutingTest {
    private class Fixture(scope: CoroutineScope) {
        private val model = ProviderModel("shared-id", "Shared", listOf(Capability("tone", "Tone", listOf("short", "long"))),
            profile = ModelProfile(externalId = "shared-wire-id"))
        private fun provider(id: String) = newProvider(Mode.Chat).copy(id = id, name = id,
            baseUrl = "https://$id.invalid/v1", models = listOf(model))
        val a = provider("source-a")
        val b = provider("chosen-b")
        fun route(provider: Provider) = RemoteModel(provider.id, model.id)
        val store = ProviderStore(object : ProviderCatalogStorage {
            private val data = mutableMapOf<String, String>()
            override fun read(key: String) = data[key]
            override fun write(key: String, value: String) { data[key] = value }
            override fun initialCatalog() = ModelCatalog(emptyList())
        })
        val requests = mutableListOf<LocalModelRequest>()
        private val repo = ChatGenerationRepository({ store.catalog.value }, store::sessionKey,
            { files, _ -> check(files.isEmpty()); emptyMap() },
        ) { protocol, request, reply ->
            check(protocol == ChatProtocol.ChatCompletionsHttp)
            requests += request
            flowOf(reply.copy(text = "Mock reply from " + request.url, status = Generation.Completed))
        }
        val chat = ConversationSession(scope, repo, {}, newRoutes = store::newThreadRoutes, showRoutes = store::showRoutes)
        init {
            for (provider in listOf(a, b)) store.save(provider, "synthetic-" + provider.id, null)
            store.onModelPicked = chat::selectModel
            store.showRoutes(chat.state.value.current.modelRoutes, null)
            pick(a, "short")
        }

        fun pick(provider: Provider, tone: String) =
            store.configure(Mode.Chat, route(provider), mapOf("tone" to tone))

        suspend fun send(text: String) {
            chat.setDraft(text)
            val state = chat.state.value
            val error = sendDraft(store, state, store.catalog.value, chat.state, { false },
                { _, _, _ -> error("Unexpected image request") }) { label, parameters, image, route ->
                chat.send(label, parameters, null, null, image, route)
            }
            assertNull(error)
        }

        suspend fun edit(
            messageId: String,
            text: String,
            before: HubState = chat.state.value,
            catalog: ModelCatalog = store.catalog.value,
        ): String? = sendEditedBranch(store, before, catalog, chat.state, messageId, text, { false },
            { _, _, _ -> error("Unexpected image request") }) { model, parameters, image, route ->
            chat.forkEditedMessage(before.current.id, messageId, text, "Branch", model, parameters, image, route)
        }

        fun lastRequestUserTurns(): List<String> {
            val messages = requests.last().body.getJSONArray("messages")
            return (0 until messages.length()).map { messages.getJSONObject(it) }
                .filter { it.getString("role") == "user" }.map { it.getString("content") }
        }

        suspend fun assertReply(provider: Provider, tone: String, question: String) {
            val reply = withTimeout(5_000) { chat.state.first { !it.isGenerating } }.current.messages.last()
            assertEquals(Generation.Completed, reply.status)
            val request = requests.last()
            val endpoint = provider.baseUrl + "/chat/completions"
            assertEquals(endpoint, request.url)
            assertEquals(route(provider), reply.remoteModel)
            assertEquals("shared-wire-id", request.body.getString("model"))
            assertEquals("Bearer synthetic-" + provider.id, request.headers["Authorization"])
            val transcript = request.body.getJSONArray("messages")
            assertEquals(question, transcript.getJSONObject(transcript.length() - 1).getString("content"))
            assertEquals(tone, request.parameters["tone"])
            assertEquals("Mock reply from $endpoint", reply.text)
        }
    }

    @Test fun editedBranchFirstAndNextRequestGoToTheSelectedProvider() = runBlocking {
        val f = Fixture(this)
        f.send("Original question")
        f.assertReply(f.a, "short", "Original question")
        f.send("Later source-only question")
        f.assertReply(f.a, "short", "Later source-only question")
        f.pick(f.b, "long")
        val source = f.chat.state.value.current
        assertEquals(f.route(f.b), f.store.catalog.value.selectedEntry(Mode.Chat)?.route)
        f.chat.setDraft("Source draft")

        assertNull(f.edit(source.messages.first().id, "Edited question"))
        f.assertReply(f.b, "long", "Edited question")
        assertEquals(listOf("Edited question"), f.lastRequestUserTurns())
        val branch = f.chat.state.value.current
        assertNotEquals(source.id, branch.id)
        assertEquals(f.route(f.b), branch.messages.first().remoteModel)
        assertEquals(listOf("Edited question", branch.messages.last().text), branch.messages.map { it.text })
        assertEquals("long", branch.messages.first().parameters["tone"])
        f.send("Follow-up")
        f.assertReply(f.b, "long", "Follow-up")
        assertEquals(listOf("Edited question", "Follow-up"), f.lastRequestUserTurns())
        f.chat.openConversation(source.id)
        assertEquals(source, f.chat.state.value.current)
        assertEquals("Source draft", f.chat.state.value.draft.text)
        assertEquals(f.route(f.b), f.store.catalog.value.selectedEntry(Mode.Chat)?.route)
        assertEquals(4, f.requests.size)
    }

    @Test fun retryOfOriginalReplyUsesItsSnapshotAndKeepsCurrentSelectionForNextSend() = runBlocking {
        val f = Fixture(this)
        f.send("Original question")
        f.assertReply(f.a, "short", "Original question")
        val original = f.chat.state.value.current
        val answer = original.messages.last()
        f.pick(f.a, "long")
        f.pick(f.b, "long")

        assertNull(f.store.catalog.value.retryChatRouteIssue(original.messages.first(), answer))
        f.chat.regenerate(answer.id)
        f.assertReply(f.a, "short", "Original question")
        val retried = f.chat.state.value.current
        assertEquals(original.id, retried.id)
        assertEquals(answer.id, retried.messages.last().id)
        assertEquals(listOf(answer), retried.versions.getValue(answer.id).others)
        assertEquals(f.route(f.b), f.store.catalog.value.selectedEntry(Mode.Chat)?.route)

        f.send("Next question")
        f.assertReply(f.b, "long", "Next question")
        assertEquals(3, f.requests.size)
    }

    @Test fun disabledOrRemovedCurrentModelLeavesEditorRecoverableWithSourceAndDraftIntact() = runBlocking {
        val f = Fixture(this)
        f.send("Original question")
        f.assertReply(f.a, "short", "Original question")
        f.pick(f.b, "long")
        f.chat.setDraft("Unrelated source draft")
        val saved = f.chat.snapshot()
        val promptId = f.chat.state.value.current.messages.first().id
        val edited = "Still editing"

        val disabled = f.b.models.single().copy(enabled = false)
        for (models in listOf(listOf(disabled), emptyList())) {
            f.store.save(f.b.copy(models = models), "synthetic-" + f.b.id, null)
            assertNull(f.store.catalog.value.selectedEntry(Mode.Chat))
            val issue = f.edit(promptId, edited)
            assertFalse("An unavailable chosen model must report an error", issue.isNullOrBlank())
            assertEquals(saved, f.chat.snapshot())
            assertEquals("Unrelated source draft", f.chat.state.value.draft.text)
            assertEquals(1, f.requests.size)
        }

        f.store.save(f.b, "synthetic-" + f.b.id, null)
        assertNull(f.edit(promptId, edited))
        f.assertReply(f.b, "short", edited)
        assertEquals(2, f.requests.size)
    }

    @Test fun staleEditorSelectionCannotStartBranchWithEitherTheOldOrTheNewModel() = runBlocking {
        val f = Fixture(this)
        f.send("Original question")
        f.assertReply(f.a, "short", "Original question")
        f.chat.setDraft("Source draft")
        val displayed = f.chat.state.value
        val catalogBeforeSwitch = f.store.catalog.value
        val promptId = displayed.current.messages.first().id
        f.pick(f.b, "long")
        val afterSwitch = f.chat.snapshot()

        assertFalse(f.edit(promptId, "Typed edit", displayed, catalogBeforeSwitch).isNullOrBlank())
        assertEquals(afterSwitch, f.chat.snapshot())
        assertEquals("Source draft", f.chat.state.value.draft.text)
        assertEquals(1, f.requests.size)

        assertNull(f.edit(promptId, "Typed edit"))
        f.assertReply(f.b, "long", "Typed edit")
    }

    @Test fun openBranchOnSameModelRejectsASecondClickFromTheSourceEditor() = runBlocking {
        val f = Fixture(this)
        f.send("Original question")
        f.assertReply(f.a, "short", "Original question")
        f.chat.setDraft("Source draft")
        val source = f.chat.state.value
        val shown = f.store.catalog.value
        val prompt = source.current.messages.first().id

        assertNull(f.edit(prompt, "Single edit", source, shown))
        f.assertReply(f.a, "short", "Single edit")
        val branch = f.chat.snapshot()
        assertFalse(f.edit(prompt, "Single edit", source, shown).isNullOrBlank())
        assertEquals(branch, f.chat.snapshot())
        assertEquals(2, f.requests.size)

        f.chat.openConversation(source.current.id)
        assertEquals(source.current, f.chat.state.value.current)
        assertEquals(source.draft, f.chat.state.value.draft)
    }

    @Test fun unavailableHistoricalProviderBlocksRetryButCurrentModelCanAnswerEditedBranch() = runBlocking {
        val f = Fixture(this)
        f.send("Original question")
        f.assertReply(f.a, "short", "Original question")
        val original = f.chat.state.value.current
        val answer = original.messages.last()
        f.pick(f.b, "long")
        f.chat.setDraft("Source draft")
        val question = original.messages.first()
        val beforeEdit = f.chat.snapshot()
        val aModel = f.a.models.single()
        val renamed = aModel.copy(profile = aModel.profile.copy(externalId = "replacement-wire-id"))
        for (changed in listOf(f.a.copy(protocol = ChatProtocol.ResponsesHttp.label), f.a.copy(models = listOf(renamed)))) {
            f.store.save(changed, "synthetic-" + f.a.id, null)
            assertFalse(f.store.catalog.value.retryChatRouteIssue(question, answer).isNullOrBlank())
        }
        f.store.remove(f.a.id)
        assertFalse(f.store.catalog.value.retryChatRouteIssue(question, answer).isNullOrBlank())
        assertEquals(beforeEdit, f.chat.snapshot())
        assertEquals(1, f.requests.size)

        f.chat.regenerate(answer.id)
        val failed = withTimeout(5_000) { f.chat.state.first { !it.isGenerating } }.current
        assertEquals(Generation.Error, failed.messages.last().status)
        assertFalse(failed.messages.last().error.isNullOrBlank())
        assertEquals(listOf(answer), failed.versions.getValue(answer.id).others)
        assertEquals(1, f.requests.size)
        assertNull(f.edit(original.messages.first().id, "Edited question"))
        f.assertReply(f.b, "long", "Edited question")

        f.chat.openConversation(original.id)
        assertEquals(failed, f.chat.state.value.current)
        assertEquals("Source draft", f.chat.state.value.draft.text)
        assertEquals(2, f.requests.size)
    }
}
