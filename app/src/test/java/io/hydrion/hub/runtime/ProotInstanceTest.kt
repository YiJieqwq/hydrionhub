package io.hydrion.hub.runtime

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class ProotInstanceTest {
    private val env = ProotEnvironment(
        "11111111-0000-4000-8000-000000000001", "22222222-0000-4000-8000-000000000002"
    )

    // No OS process or PID. The fixture records direct handle calls.
    private class Child(override val instance: ProotInstance) : OwnedProotChild {
        var cancellations = 0
        var polls = 0
        var failCancellation = false
        var failReap = false
        var status: Int? = null
        override fun requestCancellation() {
            check(!failCancellation)
            cancellations++
        }
        override fun tryReap(): Int? {
            check(!failReap)
            polls++
            return status
        }
    }

    @Test fun pendingCancellationFollowsOnlyTheDirectChildAndExitIsReapedOnce() {
        val instance = ProotInstance(env)
        val owned = Child(instance)
        val foreign = Child(ProotInstance(env))
        instance.requestCancellation()
        assertNull(instance.tryReap())
        assertThrows(IllegalArgumentException::class.java) { instance.attach(foreign) }
        instance.attach(owned)
        instance.attach(owned)
        instance.requestCancellation()
        assertEquals(1, owned.cancellations)
        assertNull(instance.tryReap())
        assertThrows(IllegalStateException::class.java) { instance.attach(Child(instance)) }
        owned.status = 137
        assertEquals(137, instance.tryReap())
        assertEquals(137, instance.tryReap())
        assertThrows(IllegalStateException::class.java) { instance.attach(owned) }
        instance.requestCancellation()
        assertEquals(2, owned.polls)
        assertEquals(1, owned.cancellations)
        assertEquals(0, foreign.cancellations)
        assertEquals(0, foreign.polls)
    }

    @Test fun handleFailuresCanRetryWithoutGrantingOwnershipToAnotherHandle() {
        val instance = ProotInstance(env)
        val owned = Child(instance)
        instance.attach(owned)
        owned.failCancellation = true
        assertThrows(IllegalStateException::class.java) { instance.requestCancellation() }
        owned.failCancellation = false
        instance.requestCancellation()
        owned.failReap = true
        assertThrows(IllegalStateException::class.java) { instance.tryReap() }
        assertThrows(IllegalStateException::class.java) { instance.attach(Child(instance)) }
        owned.failReap = false
        owned.status = 0
        assertEquals(0, instance.tryReap())
        assertEquals(1, owned.cancellations)
        assertEquals(1, owned.polls)
    }

    @Test fun racingAttachAndCancelDeliverOnceAndRacingPollsObserveOneExit() {
        val instance = ProotInstance(env)
        val owned = Child(instance)
        val threads = Executors.newFixedThreadPool(4)
        try {
            val start = CountDownLatch(1)
            val requests = List(8) { index ->
                threads.submit {
                    check(start.await(5, TimeUnit.SECONDS))
                    if (index % 2 == 0) instance.attach(owned) else instance.requestCancellation()
                }
            }
            start.countDown()
            requests.forEach { it.get(5, TimeUnit.SECONDS) }
            assertEquals(1, owned.cancellations)
            owned.status = 9
            val polls = List(8) { threads.submit<Int?> { instance.tryReap() } }
            polls.forEach { assertEquals(9, it.get(5, TimeUnit.SECONDS)) }
            assertEquals(1, owned.polls)
        } finally {
            threads.shutdownNow()
        }
    }
}
