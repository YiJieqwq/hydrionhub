package io.hydrion.hub.model

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationSessionTest {
    @Test fun historyStartsOnFirstSendAndModeLockSurvivesDeletion() = runBlocking {
        val session = ConversationSession(this, FakeGenerationRepository {}, {})
        fun screen() = session.state.value
        session.setMode(Mode.Image)
        session.setDraft("    ")
        session.send("image", emptyMap(), null, null)
        assertTrue(screen().history.isEmpty())
        session.addAttachment(Attachment("ref", "参考图", imageKey = "seed"))
        session.send("image", mapOf("n" to "2"), null, null)
        assertTrue(screen().draft.attachments.isEmpty())
        assertEquals(Generation.Sending, screen().current.messages.last().status)
        session.setMode(Mode.Chat)
        assertEquals(Mode.Image, screen().current.mode)
        yield()
        val result = screen().current.messages.last()
        assertEquals(Generation.Completed, result.status)
        assertEquals(2, result.imageKeys.size)
        session.referenceImage(result.imageKeys.first(), 1536, 1024)
        val reference = screen().draft.attachments.single()
        assertEquals(result.imageKeys.first(), reference.imageKey)
        assertEquals(1536, reference.width)
        assertEquals(1024, reference.height)
        session.deleteMessage(screen().current.messages.first().id)
        assertTrue(screen().current.messages.isEmpty())
        assertTrue(screen().modeLocked)
        session.newConversation()
        assertFalse(screen().modeLocked)
        assertEquals(1, screen().history.size)
    }

    @Test fun retryFromUserAfterDeletingImageReplyUsesCurrentImageModelAndOriginalChatBudget() = runBlocking {
        val session = ConversationSession(this, FakeGenerationRepository {}, {})
        val chatParameters = mapOf("effort" to "High", "image_budget" to "3")
        val image = ImageInvocation("studio", mapOf("size" to "1536x1024", "n" to "2"))
        session.setDraft("画一幅山水画")
        session.send("writer", chatParameters, null, null, image)
        yield()
        val (user, result) = session.state.value.current.messages
        assertEquals("writer", user.model)
        assertEquals(chatParameters, user.parameters)
        assertEquals("studio", result.model)
        assertEquals(image.parameters, result.parameters)
        assertEquals(2, result.imageKeys.size)
        session.deleteMessage(result.id)
        val selected = ImageInvocation("new studio", mapOf("n" to "1", "quality" to "high"))
        session.regenerate(user.id) { prompt, budget ->
            assertEquals(user.text, prompt)
            assertEquals(3, budget)
            selected
        }
        yield()
        val retry = session.state.value.current.messages.last()
        assertEquals(user, session.state.value.current.messages.first())
        assertEquals(selected.model, retry.model)
        assertEquals(selected.parameters, retry.parameters)
        assertEquals(1, retry.imageKeys.size)
        assertTrue(retry.tool.orEmpty().contains("自定义图片生成"))
    }

    @Test fun retryOfLegacyBuiltinImageKeepsHistoryOnConfigurationErrorThenUsesImageModel() = runBlocking {
        val errors = mutableListOf<Pair<String, String>>()
        val session = ConversationSession(this, FakeGenerationRepository {}, {},
            onGenerationError = { id, detail -> errors += id to detail })
        val oldChat = RemoteModel("legacy-provider", "legacy-chat")
        val user = Message(role = Role.User, text = "画一朵花", model = "writer", remoteModel = oldChat)
        val reply = Message(role = Role.Assistant, replyTo = user.id, model = "writer", remoteModel = oldChat,
            tool = "图片生成 · 模型内置图片生成", imageKeys = listOf("saved-image"))
        val item = Conversation(messages = listOf(user, reply))
        val saved = SavedWorkspace(listOf(item))
        session.restore(saved)
        session.openConversation(item.id)

        val detail = "请先配置图像生成模型。"
        session.regenerate(reply.id) { _, _ -> throw IllegalArgumentException(detail) }
        assertEquals(listOf(item.id to detail), errors)
        assertFalse(session.state.value.isGenerating)
        assertEquals(saved, session.snapshot())

        session.regenerate(reply.id) { _, _ -> ImageInvocation("illustrator", mapOf("n" to "2")) }
        yield()
        val retried = session.state.value.current.messages.last()
        assertEquals(reply.id, retried.id)
        assertEquals("illustrator", retried.model)
        assertEquals(null, retried.remoteModel)
        assertEquals("图片生成 · 自定义图片生成", retried.tool)
        assertEquals(2, retried.imageKeys.size)
    }

    @Test fun imageModeRetryUsesCurrentImageModelsBudget() = runBlocking {
        val session = ConversationSession(this, FakeGenerationRepository {}, {})
        session.setMode(Mode.Image)
        session.setDraft("花园")
        session.send("old studio", mapOf("image_budget" to "1"), null, null)
        yield()
        val reply = session.state.value.current.messages.last()
        session.regenerate(reply.id) { prompt, parentLimit ->
            assertEquals("花园", prompt)
            assertEquals(null, parentLimit)
            ImageInvocation("current image model", mapOf("n" to "2"))
        }
        yield()
        val retried = session.state.value.current.messages.last()
        assertEquals("current image model", retried.model)
        assertEquals(2, retried.imageKeys.size)
    }

    @Test fun stopCancelsTheRequestAndPartialReplyRemainsStopped() = runBlocking {
        val steps = Channel<Unit>(Channel.UNLIMITED)
        val session = ConversationSession(this, FakeGenerationRepository { steps.receive() }, {})
        session.setDraft("说明")
        session.send("chat", emptyMap(), null, null)
        session.setDraft("下条消息")
        session.send("chat", emptyMap(), null, null)
        assertEquals(2, session.state.value.current.messages.size)
        steps.send(Unit)
        yield()
        assertEquals(Generation.Generating, session.state.value.current.messages.last().status)
        session.stop()
        repeat(10) { steps.send(Unit) }
        yield()
        assertFalse(session.state.value.isGenerating)
        assertEquals(Generation.Stopped, session.state.value.current.messages.last().status)
        assertEquals("下条消息", session.state.value.draft.text)
    }

    @Test fun failureCanRetryAndRegenerationUsesEditedPrompt() = runBlocking {
        val session = ConversationSession(this, FakeGenerationRepository {}, {})
        session.setDraft("/error")
        session.send("chat", emptyMap(), null, null)
        yield()
        val user = session.state.value.current.messages.first()
        val error = session.state.value.current.messages.last()
        assertEquals(Generation.Error, error.status)
        session.regenerate(error.id) { _, _ -> throw AssertionError("A chat retry must keep its original model") }
        yield()
        assertEquals("chat", session.state.value.current.messages.last().model)
        assertEquals(Generation.Completed, session.state.value.current.messages.last().status)
        session.editMessage(user.id, "代码")
        session.regenerate(user.id)
        yield()
        val regenerated = session.state.value.current.messages.last()
        assertEquals("代码", regenerated.prompt)
        assertEquals(error.id, regenerated.id)
        assertTrue(regenerated.text.contains("```kotlin"))
        session.preparePrompt(regenerated.id)
        assertEquals("代码", session.state.value.draft.text)
    }

    @Test fun pendingRequestOnRestoreIsStoppedAndColdStartIsEmpty() = runBlocking {
        val steps = Channel<Unit>(Channel.UNLIMITED)
        val original = ConversationSession(this, FakeGenerationRepository { steps.receive() }, {})
        original.setDraft("first")
        original.send("chat", emptyMap(), null, null)
        val id = original.state.value.current.id
        original.togglePin(id)
        original.rename(id, "收藏")
        original.setDraft("第二条草稿")
        val saved = original.snapshot()
        original.stop()
        val restored = ConversationSession(this, FakeGenerationRepository {}, {})
        restored.restore(saved)
        assertNotEquals(id, restored.state.value.current.id)
        assertFalse(restored.state.value.modeLocked)
        assertTrue(restored.state.value.history.single().pinned)
        restored.openConversation(id)
        val state = restored.state.value
        assertEquals("收藏", state.current.title)
        assertEquals(Generation.Stopped, state.current.messages.last().status)
        assertEquals("第二条草稿", state.draft.text)
    }

    @Test fun switchingHistoryKeepsDraftAndGeneratesIntoOriginalConversation() = runBlocking {
        val session = ConversationSession(this, FakeGenerationRepository {}, {})
        session.setDraft("搜索 Kotlin")
        session.setSearch("自定义搜索")
        session.send("chat", emptyMap(), null, null)
        val firstId = session.state.value.current.id
        session.setDraft("稍后发送")
        session.newConversation()
        session.setMode(Mode.Image)
        session.setDraft("未发送图片提示词")
        yield()
        assertTrue(session.state.value.current.messages.isEmpty())
        assertEquals(Generation.Completed, session.state.value.history.single().messages.last().status)
        session.openConversation(firstId)
        val state = session.state.value
        assertEquals("稍后发送", state.draft.text)
        assertEquals("网络搜索 · 自定义搜索", state.current.messages.last().tool)
        assertEquals(Generation.Completed, state.current.messages.last().status)
        session.newConversation()
        assertEquals(Mode.Image, session.state.value.current.mode)
        assertEquals("未发送图片提示词", session.state.value.draft.text)
        session.deleteConversation(firstId)
        assertTrue(session.state.value.history.isEmpty())
    }
}
