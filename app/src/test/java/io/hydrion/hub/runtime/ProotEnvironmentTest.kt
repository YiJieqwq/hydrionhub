package io.hydrion.hub.runtime

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

    @Test fun rejectsAmbiguousIdsHostPathsAndCwdTraversal() {
        for (bad in listOf("../work", "1-2-3-4-5", env.id.uppercase(), "/data/data/elsewhere")) {
            assertThrows(IllegalArgumentException::class.java) { ProotWorkspace(bad) }
        }
        for (bad in listOf("/", "/workspace/", "/workspace-other", "workspace/docs", "/workspace/../etc",
            "/workspace/./docs", "/workspace/a//b", "/workspace/a\\b", "/workspace/x\u0000y")) {
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
}
