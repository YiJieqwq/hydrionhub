package io.hydrion.hub.provider

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ModelImportSelectionTest {
    @Test fun cancellingAfterSelectingAcrossSearchesNeverAddsAnything() = runBlocking {
        val picker = ModelImportSelection()
        val writes = mutableListOf<List<String>>()
        picker.load { listOf("model/alpha", "model/beta") }
        picker.query = " ALPHA "
        assertEquals(listOf("model/alpha"), picker.matches)
        picker.select("model/alpha", true, emptySet())
        picker.query = "beta"
        assertEquals(listOf("model/beta"), picker.matches)
        picker.select("model/beta", true, emptySet())
        assertEquals(setOf("model/alpha", "model/beta"), picker.selected)

        picker.discard()
        picker.confirm(emptySet()) { writes += it.toList() }
        assertTrue(writes.isEmpty())
        assertTrue(picker.selected.isEmpty())
    }

    @Test fun failureAndEmptyResultsNeverWriteAndRetryCommitsOnlyNewIdsOnce() = runBlocking {
        val picker = ModelImportSelection()
        val writes = mutableListOf<List<String>>()
        val configured = setOf("existing")
        val add: (Collection<String>) -> Unit = { writes += it.toList() }
        picker.load { throw IOException("fixture failure") }
        assertEquals(ModelListResult.Failed, picker.result)
        picker.confirm(configured, add)
        picker.load { emptyList() }
        assertEquals(ModelListResult.Loaded(emptyList()), picker.result)
        picker.select("missing", true, configured)
        picker.confirm(configured, add)
        assertTrue(writes.isEmpty())

        picker.load { listOf("existing", "new", "added-while-open", "new") }
        assertEquals(listOf("existing", "new", "added-while-open"), picker.matches)
        for (id in picker.matches) picker.select(id, true, configured)
        assertEquals(setOf("new", "added-while-open"), picker.selected)
        assertTrue(writes.isEmpty())
        val current = configured + "added-while-open"
        picker.confirm(current, add)
        picker.confirm(current, add)
        assertEquals(listOf(listOf("new")), writes)
    }

    @Test fun cancelledRequestPropagatesCancellationWithoutWriting() = runBlocking {
        val picker = ModelImportSelection()
        val cancelled = CancellationException("dismissed")
        try {
            picker.load { throw cancelled }
            fail("The load should propagate cancellation")
        } catch (e: CancellationException) {
            assertSame(cancelled, e)
        }
        picker.discard()
        picker.confirm(emptySet()) { fail("A cancelled request cannot import models") }
    }
}
