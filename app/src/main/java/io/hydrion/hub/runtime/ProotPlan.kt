package io.hydrion.hub.runtime

import java.io.File
import java.util.Collections

/** An explicit guest program and literal arguments. Nothing is split, expanded, or shell-quoted. */
internal class ProotGuestCommand(val executable: String, arguments: List<String>) {
    val arguments: List<String>

    init {
        require(isGuestPath(executable)) { "请使用镜像内程序的绝对路径。" }
        val snapshot = arguments.take(129)
        require(snapshot.size <= 128) { "命令参数过多。" }
        var bytes = 0
        val encoder = Charsets.UTF_8.newEncoder()
        for (arg in listOf(executable) + snapshot) {
            // Include each terminating zero. Check the length before allocating UTF-8 bytes.
            require(arg.length < 65536 && '\u0000' !in arg && encoder.canEncode(arg)) { "参数编码无效或过长。" }
            bytes += arg.toByteArray(Charsets.UTF_8).size + 1
            require(bytes <= 65536) { "参数总长度超出限制。" }
        }
        this.arguments = Collections.unmodifiableList(snapshot)
    }
}

internal sealed interface ProotPlan {
    /**
     * Resource and argv snapshot only. A PRoot bind exposes the workspace with the host app's
     * filesystem permissions. Executable authorization, isolation and network policy are separate.
     * The Android loader ABI and temporary storage are left to a future verified runtime.
     */
    class Command private constructor(val resources: ProotPreparation.Ready, command: ProotGuestCommand) : ProotPlan {
        val instance = ProotInstance(resources.environment)
        val executable: File = resources.tools.launcher

        // Upstream CLI contract: github.com/proot-me/proot/blob/master/doc/proot/manual.rst
        // Plain -r avoids implicit system binds. '!' keeps the fixed guest mount target literal.
        val argv: List<String> = Collections.unmodifiableList(listOf(
            executable.path,
            "-r", resources.rootfs.path,
            "-b", "${resources.workspace.path}:/workspace!",
            "-w", resources.guestCwd,
            command.executable
        ) + command.arguments)

        /** Complete replacement for the host process environment, including when empty. */
        val replacementEnvironment: Map<String, String> = emptyMap()

        companion object {
            /** Always inspect this registration under this store's roots. A stale Ready cannot become a plan. */
            fun inspect(
                files: ProotFiles,
                catalog: ProotCatalog,
                id: String,
                trustedDigests: Set<String>,
                command: ProotGuestCommand
            ): ProotPlan {
                val ready = when (val result = files.prepare(catalog, id, trustedDigests)) {
                    is ProotPreparation.Ready -> result
                    is ProotPreparation.Unavailable -> return result
                }
                val problem = files.commandProblem(ready, command)
                return if (problem == null) Command(ready, command) else ProotPreparation.Unavailable(problem)
            }
        }
    }
}
