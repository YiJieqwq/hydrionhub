package io.hydrion.hub.runtime

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

internal sealed interface ProotCheck {
    data class Unavailable(val reason: ProotPreparation.Reason) : ProotCheck
    enum class Outcome : ProotCheck { LauncherResponded, IncompatibleAbi, LaunchFailed, TimedOut, CleanupIncomplete }
}

/** Only the package-installed launcher gets executed, with its built-in version option. */
internal class ProotLauncherCheck(
    private val start: (ProcessBuilder) -> Process = ProcessBuilder::start,
    private val timeoutMillis: Long = 3_000,
    private val cleanupMillis: Long = 250,
) {
    suspend fun inspect(files: ProotFiles, catalog: ProotCatalog, environment: String, trustedImages: Set<String>,
                        supportedAbis: Set<String>): ProotCheck = withContext(Dispatchers.IO) {
        val ready = when (val result = files.prepare(catalog, environment, trustedImages)) {
            is ProotPreparation.Unavailable -> return@withContext ProotCheck.Unavailable(result.reason)
            is ProotPreparation.Ready -> result
        }
        val launcher = ready.tools.launcher
        val process = try {
            if (!files.sameSupportedAbi(ready, supportedAbis)) return@withContext ProotCheck.Outcome.IncompatibleAbi
            val sink = File("/dev/null")
            val builder = ProcessBuilder(launcher.path, "--version").directory(launcher.parentFile)
                .redirectInput(sink).redirectOutput(sink).redirectError(sink)
            builder.environment().clear()
            start(builder)
        } catch (_: IOException) {
            return@withContext ProotCheck.Outcome.LaunchFailed
        } catch (_: SecurityException) {
            return@withContext ProotCheck.Outcome.LaunchFailed
        }

        val instance = ProotInstance(ready.environment)
        instance.attach(DirectLauncher(instance, process))
        var reaped: Boolean
        val outcome = try {
            val exited = runInterruptible { process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS) }
            when {
                !exited -> ProotCheck.Outcome.TimedOut
                instance.tryReap() == 0 -> ProotCheck.Outcome.LauncherResponded
                else -> ProotCheck.Outcome.LaunchFailed
            }
        } finally {
            reaped = withContext(NonCancellable) {
                if (instance.tryReap() == null) {
                    instance.requestCancellation()
                    if (!process.waitFor(cleanupMillis, TimeUnit.MILLISECONDS)) {
                        process.destroyForcibly()
                        process.waitFor(cleanupMillis, TimeUnit.MILLISECONDS)
                    }
                }
                instance.tryReap() != null
            }
        }
        if (reaped) outcome else ProotCheck.Outcome.CleanupIncomplete
    }

    private class DirectLauncher(override val instance: ProotInstance, val process: Process) : OwnedProotChild {
        override fun requestCancellation() = process.destroy()
        override fun tryReap(): Int? = try { process.exitValue() } catch (_: IllegalThreadStateException) { null }
    }
}
