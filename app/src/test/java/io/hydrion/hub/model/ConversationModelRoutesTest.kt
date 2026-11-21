package io.hydrion.hub.model

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ConversationModelRoutesTest {
    private val one = RemoteModel("chat-a", "same")
    private val two = RemoteModel("chat-b", "same")
    private val image = RemoteModel("images", "same")

    @Test fun defaultChangesOnlyAffectNewThreadsAndExplicitPicksSurviveRestart() = runBlocking {
        var defaults = mapOf(Mode.Chat to one, Mode.Image to image)
        var presented: Map<Mode, RemoteModel>? = null
        fun session() = ConversationSession(this, FakeGenerationRepository {}, {},
            newRoutes = { defaults }, showRoutes = { presented = it })
        val old = decodeWorkspace(JSONObject("""{"history":[{"id":"old","title":"Old","mode":"Chat","updatedAt":7}]}"""))
        val live = session()
        live.restore(old.withLegacyModelRoutes(defaults))
        live.openConversation("old")
        defaults = defaults + (Mode.Chat to two)
        assertEquals(one, live.state.value.current.modelRoutes?.get(Mode.Chat))
        live.newConversation()
        assertEquals(two, presented?.get(Mode.Chat))
        live.selectModel(Mode.Chat, one)
        val chosen = live.state.value.current.id
        val cold = session()
        cold.restore(decodeWorkspace(encodeWorkspace(live.snapshot())).withLegacyModelRoutes(defaults))
        assertEquals(chosen, cold.state.value.current.id)
        assertEquals(mapOf(Mode.Chat to one, Mode.Image to image), presented)
        cold.newConversation()
        assertEquals(two, presented?.get(Mode.Chat))
        cold.openConversation("old")
        assertEquals(one, presented?.get(Mode.Chat))
        assertEquals(image, presented?.get(Mode.Image))
    }

    @Test fun earlyManualSelectionIsNotOverwrittenByAnAsynchronousOldDraftLoad() = runBlocking {
        val live = ConversationSession(this, FakeGenerationRepository {}, {}, newRoutes = { mapOf(Mode.Chat to one) })
        val chosen = live.state.value.current.id
        live.selectModel(Mode.Chat, two)
        live.restore(SavedWorkspace(unsent = Conversation(id = "prior", modelRoutes = mapOf(Mode.Chat to one))))
        assertEquals(chosen, live.state.value.current.id)
        assertEquals(two, live.snapshot().unsent?.modelRoutes?.get(Mode.Chat))
    }
}
