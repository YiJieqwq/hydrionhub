package io.hydrion.hub.runtime

import kotlinx.coroutines.launch
import kotlinx.coroutines.cancelAndJoin
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths

class ProotEnvironmentTest {
    @get:Rule val temporary = TemporaryFolder()

    private val digest = "a".repeat(64)
    private val container = ProotContainer("aaaaaaaa-0000-4000-8000-000000000001", digest)
    private val workspace = ProotWorkspace("bbbbbbbb-0000-4000-8000-000000000002")
    private val env = ProotEnvironment("cccccccc-0000-4000-8000-000000000003", container.id, workspace.id)
    private val second = "dddddddd-0000-4000-8000-000000000004"
    private val catalog = ProotCatalog().withContainer(container).withWorkspace(workspace).withEnvironment(env)
    private lateinit var privateDir: File
    private lateinit var nativeDir: File
    private lateinit var instance: File
    private lateinit var rootfs: File
    private lateinit var work: File
    private lateinit var files: ProotFiles

    @Before fun directories() {
        privateDir = temporary.newFolder("private")
        nativeDir = temporary.newFolder("package-libs")
        files = ProotFiles(privateDir, nativeDir)
        instance = File(privateDir, "proot-v1/containers/${container.id}")
        rootfs = File(instance, "rootfs")
        work = File(privateDir, "proot-v1/workspaces/${workspace.id}")
    }

    private fun tools() {
        // Inert test data with ELF identification bytes. No test starts any executable.
        val header = ByteArray(64)
        byteArrayOf(0x7f, 0x45, 0x4c, 0x46, 2, 1, 1).copyInto(header)
        for (name in listOf("libproot.so", "libproot-loader.so")) {
            File(nativeDir, name).apply { writeBytes(header); check(setExecutable(true, true)) }
        }
    }

    private fun provision() {
        tools()
        for (name in listOf("bin", "etc", "usr", "workspace")) check(File(rootfs, name).mkdirs())
        File(rootfs, "bin/sh").apply { writeText("inert test fixture"); check(setExecutable(true, true)) }
        File(instance, "installed.sha256").writeText("$digest\n")
        check(work.mkdirs())
    }

    private fun outcome(source: ProotCatalog = catalog, id: String = env.id, trust: Set<String> = setOf(digest)) =
        files.prepare(source, id, trust)

    private fun assertReason(reason: ProotPreparation.Reason, actual: ProotPreparation) =
        assertEquals(ProotPreparation.Unavailable(reason), actual)

    private fun compatibleTools() {
        provision()
        for (file in listOf(File(nativeDir, "libproot.so"), File(nativeDir, "libproot-loader.so"), File(rootfs, "bin/sh"))) {
            val header = ByteArray(64)
            byteArrayOf(0x7f, 0x45, 0x4c, 0x46, 2, 1, 1).copyInto(header)
            header[16] = 3
            header[18] = 183.toByte()
            file.writeBytes(header)
        }
    }

    @Test fun launcherProbeRequiresPublisherTrustAndMatchingActualElfFiles() = kotlinx.coroutines.runBlocking {
        compatibleTools()
        val probe = ProotLauncherCheck(start = { error("Must not start") })
        suspend fun inspect(abis: Set<String>, trust: Set<String> = setOf(digest)) =
            probe.inspect(files, catalog, env.id, trust, abis)
        assertEquals(ProotCheck.Unavailable(ProotPreparation.Reason.UntrustedRootfs), inspect(setOf("arm64-v8a"), emptySet()))
        assertEquals(ProotCheck.Outcome.IncompatibleAbi, inspect(setOf("x86_64")))
        File(rootfs, "bin/sh").writeText("not an executable")
        assertEquals(ProotCheck.Outcome.IncompatibleAbi, inspect(setOf("arm64-v8a")))
    }

    @Test fun probeBuildsOnlyVersionRequestAndReapsItsChildOnTimeoutAndCancellation() = kotlinx.coroutines.runBlocking {
        compatibleTools()
        val started = kotlinx.coroutines.CompletableDeferred<Process>()
        fun probe(timer: Long, duration: String) = ProotLauncherCheck(start = { request ->
            assertEquals(listOf(File(nativeDir, "libproot.so").canonicalPath, "--version"), request.command())
            assertEquals(nativeDir.canonicalFile, request.directory())
            assertTrue(request.environment().isEmpty())
            assertEquals(File("/dev/null"), request.redirectInput().file())
            assertEquals(File("/dev/null"), request.redirectOutput().file())
            assertEquals(File("/dev/null"), request.redirectError().file())
            // A known host test fixture stands in for the launcher. No guest or fixture ELF runs.
            ProcessBuilder("/bin/sleep", duration).start().also { if (duration == "20") started.complete(it) }
        }, timeoutMillis = timer)
        suspend fun run(check: ProotLauncherCheck) = check.inspect(files, catalog, env.id, setOf(digest), setOf("arm64-v8a"))
        assertEquals(ProotCheck.Outcome.LauncherResponded, run(probe(3000, "0")))
        assertEquals(ProotCheck.Outcome.TimedOut, run(probe(10, "3")))
        val job = launch { run(probe(30000, "20")) }
        val child = kotlinx.coroutines.withTimeout(5000) { started.await() }
        job.cancelAndJoin()
        assertFalse(child.isAlive)
    }

    @Test fun rejectsAmbiguousIdsHostPathsAndCwdTraversal() {
        for (bad in listOf("../work", "1-2-3-4-5", env.id.uppercase(), "/data/data/elsewhere")) {
            assertThrows(IllegalArgumentException::class.java) { ProotWorkspace(bad) }
        }
        for (bad in listOf("/", "/workspace/", "/workspace-other", "workspace/docs", "/workspace/../etc",
            "/workspace/./docs", "/workspace/a//b", "/workspace/a\\b", "/workspace/x\u0000y",
            "/workspace/\uD800", "/workspace/" + "界".repeat(1400))) {
            assertThrows(IllegalArgumentException::class.java) { env.copy(guestCwd = bad) }
        }
        assertThrows(IllegalArgumentException::class.java) { container.copy(rootfsSha256 = "../artifact") }
        assertReason(ProotPreparation.Reason.UnknownEnvironment, outcome(id = "../bad"))
    }

    @Test fun catalogValidatesReferencesAndSupportsSharedWorkspacesAndOlderMultipleContainerRecords() {
        val other = container.copy(id = second)
        assertSame(catalog, catalog.withContainer(container))
        assertThrows(IllegalArgumentException::class.java) { catalog.withContainer(other) }
        assertThrows(IllegalArgumentException::class.java) { catalog.withEnvironment(env.copy(id = second, containerId = second)) }
        assertThrows(IllegalArgumentException::class.java) { catalog.withEnvironment(env.copy(id = second, workspaceId = second)) }
        assertThrows(IllegalArgumentException::class.java) { catalog.withEnvironment(env.copy(guestCwd = "/workspace/other")) }
        val multiref = ProotCatalog(listOf(container, other), listOf(workspace), listOf(env, env.copy(id = second, containerId = second)))
        assertEquals(multiref, ProotCatalog.fromJson(JSONObject(multiref.toJson().toString())))
        assertThrows(IllegalArgumentException::class.java) {
            ProotCatalog.fromJson(multiref.toJson().put("version", 2))
        }
    }

    @Test fun unavailableToolsAndUntrustedOrIncompleteImagesRemainDistinct() {
        assertReason(ProotPreparation.Reason.RuntimeNotPackaged, outcome())
        tools()
        File(nativeDir, "libproot-loader.so").writeText("not an ELF")
        assertReason(ProotPreparation.Reason.RuntimeNotPackaged, outcome())
        tools()
        assertReason(ProotPreparation.Reason.UntrustedRootfs, outcome(trust = emptySet()))
        assertReason(ProotPreparation.Reason.IncompleteRootfs, outcome())
        provision()
        File(instance, "installed.sha256").writeText("b".repeat(64))
        assertReason(ProotPreparation.Reason.IncompleteRootfs, outcome())
        File(instance, "installed.sha256").writeText(digest)
        Files.delete(File(rootfs, "workspace").toPath())
        assertReason(ProotPreparation.Reason.IncompleteRootfs, outcome())
    }

    @Test fun resolvesOnlyTheChosenWorkspaceAndKeepsGuestAndHostWorkingDirectoriesDistinct() {
        provision()
        val docs = File(work, "中文 documents").apply { check(mkdir()) }
        val docsEnv = env.copy(id = second, guestCwd = "/workspace/中文 documents")
        val configured = catalog.withEnvironment(docsEnv)
        val home = outcome(configured) as ProotPreparation.Ready
        val other = outcome(configured, docsEnv.id) as ProotPreparation.Ready
        assertEquals(home.workspace, other.workspace)
        assertEquals(work.canonicalFile, home.hostCwd)
        assertEquals(docs.canonicalFile, other.hostCwd)
        assertEquals("/workspace/中文 documents", other.guestCwd)
        assertEquals(File(rootfs, "workspace").canonicalFile, other.workspaceMountPoint)
        assertEquals(File(nativeDir, "libproot.so").canonicalFile, other.tools.launcher)
    }

    @Test fun prepareDoesNotCreateAMissingWorkDirectoryOrWorkspace() {
        provision()
        val absent = env.copy(guestCwd = "/workspace/missing")
        assertReason(ProotPreparation.Reason.MissingWorkingDirectory, outcome(catalog.copy(environments = listOf(absent))))
        assertFalse(File(work, "missing").exists())
        Files.delete(work.toPath())
        assertReason(ProotPreparation.Reason.MissingWorkspace, outcome())
        assertFalse(work.exists())
    }

    @Test fun rejectsAliasesAtRuntimeToolsMountWorkspaceAndIntermediateCwd() {
        provision()
        val outside = temporary.newFolder("outside").toPath()
        Files.createDirectory(outside.resolve("nested"))
        val mount = File(rootfs, "workspace").toPath()
        Files.delete(mount)
        Files.createSymbolicLink(mount, outside)
        assertReason(ProotPreparation.Reason.UnsafeOrUnreadablePath, outcome())
        Files.delete(mount)
        Files.createDirectory(mount)
        val link = work.toPath().resolve("alias")
        Files.createSymbolicLink(link, outside)
        assertReason(ProotPreparation.Reason.UnsafeOrUnreadablePath,
            outcome(catalog.copy(environments = listOf(env.copy(guestCwd = "/workspace/alias/nested")))))
        Files.delete(link)
        Files.delete(work.toPath())
        Files.createSymbolicLink(work.toPath(), outside)
        assertThrows(java.io.IOException::class.java) { files.requireWorkspace(workspace) }
        assertReason(ProotPreparation.Reason.UnsafeOrUnreadablePath, outcome())
        Files.delete(work.toPath())
        Files.createDirectory(work.toPath())
        val proot = File(nativeDir, "libproot.so").toPath()
        Files.delete(proot)
        Files.createSymbolicLink(proot, File(nativeDir, "libproot-loader.so").toPath())
        assertReason(ProotPreparation.Reason.UnsafeOrUnreadablePath, outcome())
    }

    @Test fun absoluteShellLinksUseTheGuestRootAndCyclesOrEscapesFailClosed() {
        provision()
        val shell = File(rootfs, "bin/sh").toPath()
        val realShell = File(rootfs, "usr/fixture-only-shell").toPath()
        Files.move(shell, realShell)
        Files.createSymbolicLink(shell, Paths.get("/usr/fixture-only-shell"))
        assertTrue(outcome() is ProotPreparation.Ready)
        Files.delete(shell)
        Files.createSymbolicLink(shell, Paths.get("sh"))
        assertReason(ProotPreparation.Reason.IncompleteRootfs, outcome())
        Files.delete(shell)
        Files.createSymbolicLink(shell, Paths.get("../../installed.sha256"))
        assertReason(ProotPreparation.Reason.IncompleteRootfs, outcome())
    }

    @Test fun planUsesOnlyRegisteredResourcesAndPreservesLiteralArgv() {
        provision()
        check(File(work, "my docs").mkdir())
        val configured = catalog.copy(environments = listOf(env.copy(guestCwd = "/workspace/my docs")))
        Files.createSymbolicLink(File(rootfs, "bin/fixture-tool").toPath(), Paths.get("/bin/sh"))
        val args = mutableListOf("two words", "", "\$VARIABLE; *", "-b", "/host:/guest")
        val command = ProotGuestCommand("/bin/fixture-tool", args)
        val expectedArgs = args.toList()
        args.clear()
        val plan = ProotPlan.Command.inspect(files, configured, env.id, setOf(digest), command) as ProotPlan.Command
        assertEquals(listOf(
            File(nativeDir, "libproot.so").canonicalPath,
            "-r", rootfs.canonicalPath,
            "-b", "${work.canonicalPath}:/workspace!",
            "-w", "/workspace/my docs",
            "/bin/fixture-tool"
        ) + expectedArgs, plan.argv)
        assertEquals(plan.executable.path, plan.argv.first())
        assertTrue(plan.replacementEnvironment.isEmpty())
        assertEquals(configured.environments.single(), plan.instance.environment)
        assertThrows(UnsupportedOperationException::class.java) {
            (plan.argv as MutableList<String>).clear()
        }
    }

    @Test fun replanningRechecksOwnershipRegistrationGuestProgramAndWorkspace() {
        provision()
        val program = File(rootfs, "bin/tool").toPath()
        Files.createSymbolicLink(program, Paths.get("/bin/sh"))
        val command = ProotGuestCommand("/bin/tool", emptyList())
        fun plan(source: ProotCatalog = catalog, digests: Set<String> = setOf(digest)) =
            ProotPlan.Command.inspect(files, source, env.id, digests, command)
        val first = plan() as ProotPlan.Command
        val second = plan() as ProotPlan.Command
        assertNotSame(first.instance, second.instance)
        assertEquals(ProotPreparation.Unavailable(ProotPreparation.Reason.UnknownEnvironment), plan(ProotCatalog()))
        assertEquals(ProotPreparation.Unavailable(ProotPreparation.Reason.UntrustedRootfs), plan(digests = emptySet()))
        Files.delete(program)
        assertEquals(ProotPreparation.Unavailable(ProotPreparation.Reason.MissingGuestExecutable), plan())
        // Even an executable present in the image at the later bind location is ineligible.
        Files.createLink(File(rootfs, "workspace/tool").toPath(), File(rootfs, "bin/sh").toPath())
        Files.createSymbolicLink(program, Paths.get("/workspace/tool"))
        assertEquals(ProotPreparation.Unavailable(ProotPreparation.Reason.MissingGuestExecutable), plan())
        Files.delete(work.toPath())
        assertEquals(ProotPreparation.Unavailable(ProotPreparation.Reason.MissingWorkspace), plan())
    }

    @Test fun rejectsInvalidExecutablesAndBoundsLiteralArgumentCountAndEncodedSize() {
        for (path in listOf("", "sh", "--rootfs", "/bin/", "/bin//sh", "/bin/./sh", "/../bin/sh", "/x\u0000y")) {
            assertThrows(IllegalArgumentException::class.java) { ProotGuestCommand(path, emptyList()) }
        }
        for (args in listOf(listOf("a\u0000b"), listOf("\uD800"), List(129) { "x" },
            listOf("界".repeat(24_000)), listOf("a".repeat(35_000), "b".repeat(35_000)))) {
            assertThrows(IllegalArgumentException::class.java) { ProotGuestCommand("/bin/sh", args) }
        }
    }
}
