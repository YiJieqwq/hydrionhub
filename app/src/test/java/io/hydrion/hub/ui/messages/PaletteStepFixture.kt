package io.hydrion.hub.ui.messages

import io.hydrion.hub.model.Message
import io.hydrion.hub.model.ResponsesEventAccumulator
import io.hydrion.hub.model.sseEvents

/** Four recorded items from the production reducer. Each synthetic wire frame arrives 250 ms after the last. */
internal suspend fun playPaletteSteps(request: Message, emit: suspend (Message) -> Unit) {
    var clock = 0L
    val turn = ResponsesEventAccumulator(request, clockMs = { clock })
    requireNotNull(turn.javaClass.getResourceAsStream("/responses/palette-steps.sse")).bufferedReader().use { reader ->
        for ((type, data) in reader.sseEvents()) {
            clock += 250
            turn.accept(data, type).deliver(emit)
        }
    }
}
