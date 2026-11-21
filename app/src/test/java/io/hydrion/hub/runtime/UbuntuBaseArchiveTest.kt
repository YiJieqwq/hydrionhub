package io.hydrion.hub.runtime

import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class UbuntuBaseArchiveTest {
    private val fixture = UbuntuBaseArchive("fixture", "arm64-v8a",
        "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad")

    @Test fun identifiesBytesAndAbiIndependentlyOfNameAndNeverAcceptsTruncation() = runBlocking {
        suspend fun check(bytes: String, abi: String = fixture.androidAbi, limit: Long = 3) =
            inspectUbuntuBase({ bytes.byteInputStream() }, listOf(abi), listOf(fixture), limit)
        assertEquals(ArchiveMatch.Published(fixture, true), check("abc"))
        assertEquals(ArchiveMatch.Published(fixture, false), check("abc", "x86_64"))
        assertEquals(ArchiveMatch.Unknown, check("ab"))
        assertEquals(ArchiveMatch.Unknown, check("abd"))
        assertEquals(ArchiveMatch.TooLarge, check("abcd"))
        assertEquals(ArchiveMatch.Unknown, inspectUbuntuBase({ "abc".byteInputStream() }, listOf("arm64-v8a")))
        assertEquals(setOf("ubuntu-base-26.04.1-base-arm64.tar.gz","ubuntu-base-26.04.1-base-amd64.tar.gz"),
            ubuntuBase260401.map { it.filename }.toSet())
        assertTrue(ubuntuBase260401.all { it.digest.matches(Regex("[0-9a-f]{64}")) })
    }

    @Test fun cancelledInspectionClosesTheInputWithoutPublishingAMatch() = runBlocking {
        val entered = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val stream = object : InputStream() {
            override fun read(): Int = error("use block reads")
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                entered.countDown()
                CountDownLatch(1).await()
                return -1
            }
            override fun close() { closed.countDown() }
        }
        val job = async(Dispatchers.Default) { inspectUbuntuBase({ stream }, listOf("arm64-v8a")) }
        try { assertTrue(entered.await(5, TimeUnit.SECONDS)) } finally { job.cancelAndJoin() }
        assertTrue(job.isCancelled)
        assertEquals(0L, closed.count)
    }
}
