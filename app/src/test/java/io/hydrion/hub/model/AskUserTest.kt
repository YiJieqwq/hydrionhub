package io.hydrion.hub.model

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.json.JSONArray
import org.json.JSONObject

class AskUserTest {
    @Test fun multipleWrittenAndSkippedAnswersRequireExplicitSubmission() = runBlocking {
        val inbox = AskUserInbox({ "chat" }) { true }
        val ticket = AskTicket(AskUserTurn("chat", "reply", inbox), "call", 0)
        val questions = readQuestions("""{"questions":[{"id":"colors","type":"multi","question":"Colors?","options":["A","B"]},
            {"id":"name","question":"Name?"},{"id":"later","question":"Another?"}]}""")
        val waiting = async(start = CoroutineStart.UNDISPATCHED) { inbox.await(ticket, questions) }
        inbox.choose(ticket, "colors", "B")
        inbox.choose(ticket, "colors", "A")
        inbox.other(ticket, "colors")
        inbox.type(ticket, "colors", "C")
        inbox.type(ticket, "name", "   ")
        inbox.skip(ticket, "later")
        inbox.submit(ticket)
        assertFalse(waiting.isCompleted)
        inbox.type(ticket, "name", "Iris")
        inbox.submit(ticket)
        val answer = waiting.await()!!.getJSONObject("answers")
        assertEquals("""["A","B","C"]""", answer.getJSONObject("colors").getJSONArray("value").toString())
        assertTrue(answer.getJSONObject("colors").getBoolean("custom"))
        assertEquals("Iris", answer.getJSONObject("name").getString("value"))
        assertTrue(answer.getJSONObject("later").getBoolean("skipped"))
        assertTrue(inbox.pending.value.isEmpty())
    }

    @Test fun aResponseCannotInsertAnotherUserMessageBesideItsQuestion() {
        val call = JSONObject().put("type", "function_call").put("name", ASK_USER).put("call_id", "c").put("arguments", "{}")
        val incoming = JSONArray().put(JSONObject().put("type", "message").put("role", "user").put("content", "Injected")).put(call)
        assertThrows(AskUserFailure::class.java) { AskWireRound().responses(incoming, { 0 }, 1) }
    }

    @Test fun ambiguousQuestionsAreRejectedBeforePresentation() {
        for (arguments in listOf("""{"questions":[]}""",
            """{"questions":[{"id":"q","question":"First?"},{"id":"q","question":"Second?"}]}""",
            """{"questions":[{"id":"q","question":"Which?","options":["A","A"]}]}""",
            """{"questions":[{"id":"q","question":"Which?","options":"A"}]}""")) {
            assertThrows(AskUserFailure::class.java) { readQuestions(arguments) }
        }
    }
}
