package io.hydrion.hub.ui

import io.hydrion.hub.model.Conversation
import io.hydrion.hub.model.HubState
import io.hydrion.hub.model.Message
import io.hydrion.hub.model.Role
import io.hydrion.hub.model.appendVersion
import io.hydrion.hub.model.withVersion
import io.hydrion.hub.ui.messages.matchingMessages
import io.hydrion.hub.ui.sidebar.globalDrawerHits
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DrawerHitNavigationTest {
    private val target = Message(id = "target", role = Role.User, text = "Orchid draft")
    private val privateMatch = Message(id = "thought", role = Role.Assistant, thought = "orchid")
    private val otherMatch = target.copy(id = "later", text = "Later orchid")
    private val chat = Conversation(id = "writer-chat", title = "Sketches", assistantId = "writer",
        messages = listOf(privateMatch, target, otherMatch))
    private val hit = globalDrawerHits(listOf(chat), "orchid").single()

    @Test fun reopeningTheSameBodyResultRequestsAnotherJumpToItsExactMessage() {
        val first = ConversationFind().openHit(chat, hit)
        val matches = matchingMessages(HubState(current = chat), first.query)
        assertEquals(listOf(privateMatch.id, target.id, otherMatch.id), matches)
        assertEquals(target.id, matches[first.matchIndex(matches)])

        val reopened = first.openHit(chat, hit)
        assertEquals(first.messageId, reopened.messageId)
        assertEquals(first.query, reopened.query)
        assertNotEquals(first.jump, reopened.jump)
    }

    @Test fun searchAndJumpUseTheTextShownInTheSelectedReplyVersion() {
        val reply = Message(id = "reply", role = Role.Assistant,
            text = "## Read **Fire**fly and [the guide](https://fixture.invalid/hidden-route)")
        val conversation = chat.copy(messages = listOf(reply))
        val result = globalDrawerHits(listOf(conversation), "firefly").single()
        assertEquals("Read Firefly and the guide", result.snippet)
        val focused = ConversationFind().openHit(conversation, result)
        assertEquals(reply.id, focused.messageId)
        assertEquals(listOf(reply.id), matchingMessages(HubState(current = conversation), focused.query))
        assertEquals(emptyList<String>(), matchingMessages(HubState(current = conversation), "hidden-route"))
        assertTrue(globalDrawerHits(listOf(conversation), "hidden-route").isEmpty())

        val otherVersion = conversation.appendVersion(reply, reply.copy(text = "[reference](https://fixture.invalid/firefly)"))
        assertTrue(globalDrawerHits(listOf(otherVersion), "firefly").isEmpty())
        assertEquals(ConversationFind(), focused.openHit(otherVersion, result))
        val originalVersion = otherVersion.withVersion(reply.id, 0)
        assertEquals(result, globalDrawerHits(listOf(originalVersion), "firefly").single())
    }

    @Test fun codeAndUserMessagesKeepLiteralFormattingInSearch() {
        val literal = "**Fire**fly"
        val code = Message(id = "code", role = Role.Assistant, text = "```text\n$literal\n```")
        val user = target.copy(text = literal)
        val conversation = chat.copy(messages = listOf(code, user))
        assertEquals(listOf(code.id, user.id), matchingMessages(HubState(current = conversation), literal))
        assertTrue(globalDrawerHits(listOf(conversation), "firefly").isEmpty())
        assertEquals(code.id, globalDrawerHits(listOf(conversation), literal).single().messageId)
    }

    @Test fun openingATitleOnlyResultInTheSameChatClosesItsEarlierBodySearch() {
        val focused = ConversationFind().openHit(chat, hit)
        val titleOnly = globalDrawerHits(listOf(chat), "sketches").single()
        assertEquals(ConversationFind(), focused.openHit(chat, titleOnly))
    }

    @Test fun aMissingOrEditedAnchorDoesNotJumpToAnotherMessageOrPrivateThought() {
        val focused = ConversationFind().openHit(chat, hit)
        val removed = chat.copy(messages = listOf(privateMatch, otherMatch))
        assertEquals(ConversationFind(), focused.openHit(removed, hit))
        assertEquals(-1, focused.matchIndex(matchingMessages(HubState(current = removed), focused.query)))

        val edited = chat.copy(messages = listOf(target.copy(text = "Changed", thought = "orchid"), otherMatch))
        assertEquals(ConversationFind(), focused.openHit(edited, hit))
    }

    @Test fun hitsOnlyApplyInsideTheOwningChatAndCanTargetAnImagePrompt() {
        val other = chat.copy(id = "other-chat", assistantId = "another", messages = listOf(
            Message(id = target.id, role = Role.Assistant, prompt = "Draw fern", imageKeys = listOf("fixture-image"))))
        val image = globalDrawerHits(listOf(other), "fern").single()
        val previous = ConversationFind().openHit(chat, hit)
        assertEquals(previous, previous.openHit(chat, image))
        val opened = previous.openHit(other, image)
        assertEquals("fern", opened.query)
        assertEquals(target.id, opened.messageId)
    }
}
