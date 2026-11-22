package io.hydrion.hub.provider

import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal sealed interface ModelListResult {
    data object Loading : ModelListResult
    data object Failed : ModelListResult
    data class Loaded(val ids: List<String>) : ModelListResult
}

internal class ModelImportSelection {
    var result by mutableStateOf<ModelListResult>(ModelListResult.Loading)
        private set
    var query by mutableStateOf("")
    var selected by mutableStateOf(emptySet<String>())
        private set
    private var closed = false

    val matches by derivedStateOf {
        val term = query.trim()
        (result as? ModelListResult.Loaded)?.ids.orEmpty().filter { it.contains(term, ignoreCase = true) }
    }

    suspend fun load(fetchModels: suspend () -> List<String>) {
        if (closed) return
        selected = emptySet()
        result = ModelListResult.Loading
        val next = try {
            ModelListResult.Loaded(fetchModels().distinct())
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            ModelListResult.Failed
        }
        currentCoroutineContext().ensureActive()
        if (!closed) result = next
    }

    fun select(id: String, checked: Boolean, configured: Set<String>) {
        if (closed || id in configured || id !in (result as? ModelListResult.Loaded)?.ids.orEmpty()) return
        selected = if (checked) selected + id else selected - id
    }

    fun discard() {
        closed = true
        selected = emptySet()
    }

    fun confirm(configured: Set<String>, onAdd: (Collection<String>) -> Unit) {
        val ids = selected - configured
        if (closed || result !is ModelListResult.Loaded || ids.isEmpty()) return
        discard()
        onAdd(ids)
    }
}
