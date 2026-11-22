package io.hydrion.hub.ui.chrome

import io.hydrion.hub.model.*
import io.hydrion.hub.runtime.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ConversationEnvironmentNameTest {
    @Test fun headerResolvesBindingsThroughTheCurrentThreadAcrossSwitchesAndRenames() = runBlocking {
        val container = ProotContainer(newId(), null, "Container only")
        val first = ProotEnvironment(newId(), container.id, name = "Alpha")
        val second = ProotEnvironment(newId(), container.id, name = "乙工作区")
        var catalog = ProotCatalog(listOf(container), listOf(ProotWorkspace(first.workspaceId), ProotWorkspace(second.workspaceId)),
            listOf(first, second))
        val a = Conversation(title = "A", environmentId = first.id)
        val b = Conversation(title = "B", environmentId = second.id)
        val session = ConversationSession(this, FakeGenerationRepository {}, {})
        session.restore(SavedWorkspace(history = listOf(a, b)))
        suspend fun label() = session.state.value.current.boundEnvironmentName { id -> catalog.environments.single { it.id == id } }
        session.openConversation(a.id)
        assertEquals("Alpha", label())
        session.openConversation(b.id)
        assertEquals("乙工作区", label())
        session.bindEnvironment(b.id, null)
        assertNull(label())
        session.openConversation(a.id)
        catalog = catalog.renameEnvironment(first.id, "Renamed")
        assertEquals("Renamed", label())
        val legacy = first.copy(name = "")
        assertEquals(legacy.id.take(8), a.boundEnvironmentName { legacy })
    }
}
