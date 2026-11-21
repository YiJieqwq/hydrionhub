package io.hydrion.hub.ui

import io.hydrion.hub.model.Attachment
import io.hydrion.hub.model.Conversation
import io.hydrion.hub.model.Draft
import io.hydrion.hub.model.Generation
import io.hydrion.hub.model.HubState
import io.hydrion.hub.model.Message
import io.hydrion.hub.model.Mode
import io.hydrion.hub.model.RemoteModel
import io.hydrion.hub.model.Role
import io.hydrion.hub.provider.ModelCatalog
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SendDraftTest {
    @Test fun submissionRequiresTheSameConversationModeFullDraftAndSelectedModel() {
        val submitted = HubState(draft = Draft("text", listOf(Attachment("photo", "photo.png"))))
        val catalog = ModelCatalog(emptyList(), active = mapOf(Mode.Chat to RemoteModel("first", "same-name")))
        fun accepts(live: HubState = submitted, chosen: ModelCatalog = catalog) = live.canSendDraft(submitted, chosen, catalog)
        assertTrue(accepts())
        assertFalse(accepts(submitted.copy(current = Conversation())))
        assertFalse(accepts(submitted.copy(current = submitted.current.copy(mode = Mode.Image))))
        assertFalse(accepts(submitted.copy(draft = submitted.draft.copy(text = "new text"))))
        assertFalse(accepts(submitted.copy(draft = submitted.draft.copy(attachments = emptyList()))))
        assertFalse(accepts(chosen = catalog.copy(active = mapOf(Mode.Chat to RemoteModel("second", "same-name")))))
        val activeReply = Message(role = Role.Assistant, status = Generation.Generating)
        val busy = submitted.copy(current = submitted.current.copy(messages = listOf(activeReply)))
        assertFalse(accepts(busy))
        assertFalse(submitted.canSendDraft(busy, catalog, catalog))
    }
}
