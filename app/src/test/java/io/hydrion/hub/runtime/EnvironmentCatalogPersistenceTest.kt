package io.hydrion.hub.runtime

import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import java.nio.file.AccessDeniedException
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class EnvironmentCatalogPersistenceTest {
    @get:Rule val temp = TemporaryFolder()
    private val app by lazy {
        object : ContextWrapper(ApplicationProvider.getApplicationContext<Context>()) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir() = temp.root
        }
    }

    @Test fun refusedReplacementKeepsTheSavedCatalogAndRollsBackOnlyTheNewWorkspace() = runBlocking {
        val first = ProotEnvironmentStore(app)
        first.saveContainer(null, "Fixture container", null)
        val container = first.catalog().containers.single()
        first.createEnvironment(container.id, "Keep this environment")
        val before = first.catalog()
        val workspaces = temp.root.resolve("proot-v1/workspaces")
        val marker = workspaces.resolve(before.environments.single().workspaceId).resolve("keep.txt")
        marker.writeText("existing file")

        val blocked = ProotEnvironmentStore(app) { staged, target ->
            assertEquals(before, ProotCatalog.fromJson(JSONObject(target.toFile().readText())))
            val next = ProotCatalog.fromJson(JSONObject(staged.toFile().readText()))
            assertEquals(2, next.environments.size)
            throw AccessDeniedException(target.toString())
        }
        try {
            blocked.createEnvironment(container.id, "Uncommitted")
            fail("Creation must report a refused catalog replacement")
        } catch (_: AccessDeniedException) {
            assertEquals(0, blocked.revision.value)
        }
        assertEquals(before, ProotEnvironmentStore(app).catalog())
        assertEquals(setOf(before.environments.single().workspaceId), workspaces.list()!!.toSet())
        assertEquals("existing file", marker.readText())
        assertFalse(temp.root.resolve("proot-environments-v1.json.new").exists())
        first.createEnvironment(container.id, "Retry")
        assertEquals(listOf("Keep this environment", "Retry"), ProotEnvironmentStore(app).catalog().environments.map { it.name })
    }

    @Test fun restartRestoresTheLegacyBackupAndIgnoresUncommittedStaging() = runBlocking {
        val base = temp.root.resolve("proot-environments-v1.json")
        val staged = temp.root.resolve(base.name + ".new")
        val backup = temp.root.resolve(base.name + ".bak")
        staged.writeText("interrupted first save")
        val store = ProotEnvironmentStore(app)
        assertEquals(ProotCatalog(), store.catalog())
        store.saveContainer(null, "Committed", null)
        val saved = store.catalog()

        Files.copy(base.toPath(), backup.toPath())
        base.writeText("interrupted legacy save")
        staged.writeText("interrupted staging")
        val reopened = ProotEnvironmentStore(app)
        assertEquals(saved, reopened.catalog())
        assertFalse(backup.exists())
        reopened.createEnvironment(saved.containers.single().id, "Restored")
        assertFalse(staged.exists())
        assertEquals("Restored", ProotEnvironmentStore(app).catalog().environments.single().name)
    }
}
