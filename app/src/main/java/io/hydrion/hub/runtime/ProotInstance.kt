package io.hydrion.hub.runtime

/**
 * A future launcher may implement this only around the direct child it created for [instance].
 * Both operations must be nonblocking, idempotent and must not call back into the instance.
 * A reaper returns an exit code only after it has collected the child's exit status.
 */
internal interface OwnedProotChild {
    val instance: ProotInstance
    fun requestCancellation()
    fun tryReap(): Int?
}

/** One in-memory invocation. Another invocation of the same environment has a different identity. */
internal class ProotInstance(val environment: ProotEnvironment) {
    private var child: OwnedProotChild? = null
    private var cancellationRequested = false
    private var cancellationDelivered = false
    private var exitCode: Int? = null

    @Synchronized
    fun attach(owned: OwnedProotChild) {
        require(owned.instance === this) { "进程句柄不属于此次运行。" }
        check(exitCode == null && (child == null || child === owned)) { "运行实例已结束或已持有其他句柄。" }
        child = owned
        deliverCancellation()
    }

    @Synchronized
    fun requestCancellation() {
        if (exitCode != null) return
        cancellationRequested = true
        deliverCancellation()
    }

    private fun deliverCancellation() {
        val owned = child ?: return
        if (!cancellationRequested || cancellationDelivered) return
        check(owned.instance === this)
        owned.requestCancellation()
        cancellationDelivered = true
    }

    /** Also safe before attachment. A completed invocation retains just its exit code. */
    @Synchronized
    fun tryReap(): Int? {
        exitCode?.let { return it }
        val owned = child ?: return null
        check(owned.instance === this)
        val status = owned.tryReap() ?: return null
        exitCode = status
        child = null
        return status
    }
}
