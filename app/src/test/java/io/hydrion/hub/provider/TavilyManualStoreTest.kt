package io.hydrion.hub.provider

import android.content.SharedPreferences
import java.lang.reflect.Proxy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test

class TavilyManualStoreTest {
    private val calls = TavilyFixtureCalls()
    private val key = "fixture-session-only"
    private fun port(store: TavilyManualStore, find: (String) -> SearchEndpoint? = { null }) =
        DefaultManualSearchPort(store, find, SearxngSearch(OkHttpClient.Builder().addInterceptor {
            error("unexpected SearXNG request")
        }), TavilySearch(calls))

    @Test fun togglesAndRestartsTouchOnlyTwoBooleansBesideTheLegacyFile() = runBlocking {
        val original = """{ "localEnabled":false, "endpoints":[
            {"id":"old","name":"Saved","url":"https://old.invalid"},
            {"id":"sx","name":"S","url":"https://sx.invalid","adapter":"searxng"}]}"""
        val files = MemoryPreferences(original)
        fun openPort() = port(TavilyManualStore(files::open))
        val initial = openPort()
        assertEquals(TavilySetup(false, false, false), initial.tavily.value)
        assertEquals(setOf("web_search_v1"), files.values.keys)
        initial.setPilotEnabled(true)
        initial.saveTavily(true, key)
        initial.saveTavily(true)
        assertEquals(TavilySetup(true, true, true), initial.tavily.value)
        val writes = files.writes
        val invalid = runCatching { initial.saveTavily(true, "bad\r\nkey") }.exceptionOrNull() as SearchFailure
        assertEquals("InvalidRequest", invalid.message)
        assertEquals(writes, files.writes)
        assertTrue(initial.tavily.value.hasKey)
        val reloaded = openPort()
        assertEquals(TavilySetup(true, true, false), reloaded.tavily.value)
        val failure = runCatching { reloaded.search(ManualTarget.Tavily, "query") }.exceptionOrNull() as SearchBlocked
        assertEquals(SearchBlocked.Reason.MissingCredential, failure.reason)
        initial.setPilotEnabled(false)
        initial.saveTavily(true, key) // A stale editor saving after the pilot was disabled.
        assertFalse(initial.tavily.value.hasKey)
        initial.setPilotEnabled(true)
        assertFalse(initial.tavily.value.hasKey)
        initial.setPilotEnabled(false)
        assertEquals(TavilySetup(false, true, false), openPort().tavily.value)
        assertEquals(mapOf("added" to true, "enabled" to false), files.values["web_search_tavily_v1"])
        val raw = files.values.getValue("web_search_v1")["settings"] as String
        assertArrayEquals(original.toByteArray(), raw.toByteArray())
        val legacy = WebSearchSettings.decode(raw)
        assertFalse(legacy.localEnabled)
        assertEquals(listOf(SearchAdapter.None, SearchAdapter.Searxng), legacy.endpoints.map { it.adapter })
        assertEquals(setOf("web_search_v1", "web_search_tavily_v1"), files.values.keys)
        assertFalse(files.values.toString().contains(key))
        assertTrue(calls.calls.isEmpty())
    }

    @Test fun missingLegacyTargetsDisabledPilotAndAbsentKeyUseZeroCalls() = runBlocking {
        val old = SearchEndpoint("tavily", "Old", "https://old.invalid", SearchAdapter.None)
        val port = port(TavilyManualStore()) { if (it == old.id) old else null }
        suspend fun blocked(target: ManualTarget, reason: SearchBlocked.Reason) {
            val e = runCatching { port.search(target, "q") }.exceptionOrNull() as SearchBlocked
            assertEquals(reason, e.reason)
        }
        for (target in listOf(ManualTarget.Searxng("tavily"), ManualTarget.Searxng("gone"), ManualTarget.Tavily)) {
            blocked(target, SearchBlocked.Reason.TargetUnavailable)
        }
        port.setPilotEnabled(true)
        blocked(ManualTarget.Tavily, SearchBlocked.Reason.TargetUnavailable)
        port.saveTavily(true)
        blocked(ManualTarget.Tavily, SearchBlocked.Reason.MissingCredential)
        port.saveTavily(true, key)
        port.saveTavily(true, " ")
        blocked(ManualTarget.Tavily, SearchBlocked.Reason.MissingCredential)
        assertTrue(calls.calls.isEmpty())
    }

    @Test fun invalidationCancelsOldCallsAndLateRepliesCannotChangeTheNextRun() = runBlocking {
        val changes = listOf<(DefaultManualSearchPort) -> Unit>(
            { it.setPilotEnabled(false) }, { it.saveTavily(false) },
            { it.saveTavily(true, "another-session-only-key") }, { it.saveTavily(true, "") },
        )
        for (change in changes) {
            val port = port(TavilyManualStore())
            port.setPilotEnabled(true)
            port.saveTavily(true, key)
            val a = async(start = CoroutineStart.UNDISPATCHED) { port.search(ManualTarget.Tavily, "A") }
            val first = calls.calls.last()
            change(port)
            assertTrue(first.isCanceled())
            port.setPilotEnabled(true)
            port.saveTavily(true, key)
            val b = async(start = CoroutineStart.UNDISPATCHED) { port.search(ManualTarget.Tavily, "B") }
            val second = calls.calls.last()
            assertNotSame(first, second)
            first.reply("""{"results":[{"url":"https://result.invalid/a"}]}""")
            assertFalse(b.isCompleted)
            second.reply("""{"results":[{"url":"https://result.invalid/b"}]}""")
            assertEquals("https://result.invalid/b", b.await().single().url)
            assertTrue(runCatching { a.await() }.exceptionOrNull() is CancellationException)
        }
    }

    @Test fun leavingThePageCancelsOnlyItsCallAndDiscardsALateNetworkError() = runBlocking {
        val port = port(TavilyManualStore())
        port.setPilotEnabled(true)
        port.saveTavily(true, key)
        val a = async(start = CoroutineStart.UNDISPATCHED) { port.search(ManualTarget.Tavily, "A") }
        val b = async(start = CoroutineStart.UNDISPATCHED) { port.search(ManualTarget.Tavily, "B") }
        a.cancel()
        assertTrue(calls.calls.first().isCanceled())
        assertFalse(calls.calls.last().isCanceled())
        calls.calls.first().fail()
        calls.calls.last().reply("""{"results":[]}""")
        assertTrue(b.await().isEmpty())
        assertTrue(runCatching { a.await() }.exceptionOrNull() is CancellationException)
    }

    /** Exercises the same file selection and Android editor operations used by the production constructor. */
    private class MemoryPreferences(legacy: String) {
        val values = mutableMapOf("web_search_v1" to mutableMapOf<String, Any>("settings" to legacy))
        var writes = 0
        fun open(name: String, mode: Int): SharedPreferences {
            assertEquals(0, mode)
            return proxy<SharedPreferences> { _, method, args ->
                when (method) {
                    "getBoolean" -> values[name]?.get(args[0] as String) ?: args[1]
                    "edit" -> {
                        val pending = mutableMapOf<String, Boolean>()
                        proxy<SharedPreferences.Editor> { editor, operation, fields ->
                            when (operation) {
                                "putBoolean" -> {
                                    pending[fields[0] as String] = fields[1] as Boolean
                                    editor
                                }
                                "apply" -> {
                                    values.getOrPut(name) { mutableMapOf() }.putAll(pending)
                                    writes++
                                    null
                                }
                                else -> error("Unexpected preference edit: $operation")
                            }
                        }
                    }
                    else -> error("Unexpected preference access: $method")
                }
            }
        }

        private inline fun <reified T> proxy(crossinline invoke: (Any, String, Array<out Any?>) -> Any?): T =
            Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { proxy, method, args ->
                invoke(proxy, method.name, args ?: emptyArray())
            } as T
    }
}
