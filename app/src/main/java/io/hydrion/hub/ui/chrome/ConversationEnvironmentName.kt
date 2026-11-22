package io.hydrion.hub.ui.chrome

import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import androidx.compose.ui.res.stringResource
import io.hydrion.hub.R
import io.hydrion.hub.model.Conversation
import io.hydrion.hub.runtime.ProotEnvironment
import kotlinx.coroutines.CancellationException

internal suspend fun Conversation.boundEnvironmentName(lookup: suspend (String) -> ProotEnvironment?): String? {
    val environment = environmentId?.let { lookup(it) } ?: return null
    return environment.name.ifBlank { environment.id.take(8) }
}

@Composable
internal fun conversationEnvironmentName(chat: Conversation, lookup: suspend (String) -> ProotEnvironment?): String? {
    val id = chat.environmentId ?: return null
    val unavailable = stringResource(R.string.header_environment_unavailable)
    // Settings owns renames. Re-entering chat reloads the name, as does changing the thread or binding.
    return key(chat.id, id) {
        produceState("…", unavailable) {
            value = try {
                chat.boundEnvironmentName(lookup) ?: unavailable
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                unavailable
            }
        }.value
    }
}
