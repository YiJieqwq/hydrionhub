package io.hydrion.hub.model

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.Color
import androidx.core.content.FileProvider
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Test

class PortableChatDeviceTest {
    @Test fun roundTripOfflineAfterOriginalDocumentIsUnavailable() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        PortableChatDeviceProbe.run(app, app)
    }
}

internal object PortableChatDeviceProbe {
    @JvmStatic fun run(source: Context, fresh: Context) = runBlocking {
        val permissionScope = File(source.cacheDir, "copies").apply { mkdirs() }
        val external = File.createTempFile("document-", ".txt", permissionScope)
        val value = "picked-" + newId()
        external.writeText(value)
        val uri = FileProvider.getUriForFile(source, source.packageName + ".images", external)
        val attachment = Attachment(uri.toString(), "selected.txt", uri.toString(), mimeType = "text/plain")
        val sourceMedia = GeneratedImageStore(source)
        val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        val png = ByteArrayOutputStream().apply {
            bitmap.eraseColor(Color.MAGENTA)
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, this))
        }.toByteArray()
        bitmap.recycle()
        val key = sourceMedia.saveBatch(listOf(png)).single()
        val history = Conversation(messages = listOf(Message(role = Role.Assistant, imageKeys = listOf(key))))
        val oldDraft = Draft("old-thread", listOf(attachment))
        val from = ConversationSession(this, FakeGenerationRepository {}, {})
        from.restore(SavedWorkspace(listOf(history), mapOf(history.id to oldDraft)))
        from.setDraft("unsent")
        from.addAttachment(attachment)
        val newChat = from.state.value.current.id
        val sourceBackup = LocalChatBackup(source, sourceMedia)
        val out = sourceBackup.workDirectory()
        val destImages = GeneratedImageStore(fresh)
        val destBackup = LocalChatBackup(fresh, destImages)
        val input = destBackup.workDirectory()
        val downloads = File(fresh.cacheDir, "copies").apply { mkdirs() }
        val transfer = File.createTempFile("portable-", ".zip", downloads)
        val database = Files.createTempDirectory(fresh.cacheDir.toPath(), "clean-database-").toFile()
        val freshHistory = HistoryFile(object : ContextWrapper(fresh) { override fun getFilesDir() = database })
        var installed: File? = null
        try {
            val exported = sourceBackup.export(out, from.snapshot(true))
            check(sourceBackup.preparedExport(out.name) == exported)
            exported.copyTo(transfer, true)
            check(external.delete())
            sourceMedia.discard(listOf(key))
            check(runCatching { fresh.contentResolver.openInputStream(uri)?.use { it.read() } }.isFailure)
            val archiveUri = FileProvider.getUriForFile(fresh, fresh.packageName + ".images", transfer)
            val preview = destBackup.inspect(input, archiveUri)
            check(freshHistory.load() == SavedWorkspace())
            suspend fun restore(write: suspend (SavedWorkspace) -> Unit) = destBackup.withMedia(preview, commit = write)
            val failed = runCatching { restore { throw java.io.IOException("fixture") } }
            check(failed.isFailure && freshHistory.load() == SavedWorkspace())
            check(runCatching { destImages.requireAvailable(key) }.isFailure)
            restore { state ->
                destImages.requireAvailable(key) // Original is present before the history is published.
                freshHistory.save(mergeBackup(SavedWorkspace(), state))
            }
            val cold = ConversationSession(this, FakeGenerationRepository {}, {})
            cold.restore(freshHistory.load())
            check(cold.state.value.current.id == newChat && !cold.state.value.modeLocked)
            check(cold.state.value.draft.text == "unsent")
            val restored = cold.state.value.draft.attachments.single()
            check(restored.uri != uri.toString())
            val remapped = android.net.Uri.parse(restored.uri!!)
            installed = File(File(fresh.filesDir, "restored-attachments"), remapped.lastPathSegment!!)
            check(fresh.contentResolver.openInputStream(remapped)!!.bufferedReader().use { it.readText() } == value)
            cold.openConversation(history.id)
            check(cold.state.value.draft == Draft("old-thread", listOf(restored)))
            val read = ByteArrayOutputStream().apply { destImages.copyPng(key, this) }
            check(read.toByteArray().contentEquals(png))
            val staged = destBackup.stageRollback(SavedWorkspace(), cold.snapshot(true))
            check(staged.length() > 0)
            staged.delete()
            checkViewModelTransaction(fresh, database, history.id)
        } finally {
            installed?.delete()
            sourceMedia.discard(listOf(key))
            destImages.discard(listOf(key))
            out.deleteRecursively(); input.deleteRecursively(); database.deleteRecursively()
            external.delete(); transfer.delete()
        }
    }


    private suspend fun checkViewModelTransaction(base: Context, directory: File, existingId: String) {
        val privateFiles = File(directory, "vm").apply { mkdir() }
        val storage = object : ContextWrapper(base) {
            override fun getFilesDir() = privateFiles
            override fun getNoBackupFilesDir() = File(privateFiles, "rollback").apply { mkdirs() }
        }
        val app = object : Application() {
            init { attachBaseContext(storage) }
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String, mode: Int) =
                base.getSharedPreferences("backup-test-${directory.name}", mode)
        }
        val before = SavedWorkspace(listOf(Conversation(id = existingId, title = "original")))
        val disk = HistoryFile(app)
        disk.save(before)
        val vm = withContext(Dispatchers.Main) { HubViewModel(app) }
        val pending = Conversation()
        val addition = SavedWorkspace(drafts = mapOf(pending.id to Draft("fresh draft")), unsent = pending)
        val incoming = InspectedChatArchive(addition, emptyMap(), emptyMap())
        check(withTimeoutOrNull(20_000) {
            while (withContext(Dispatchers.Main) { runCatching { vm.backupSnapshot() }.isFailure }) delay(10)
            true
        } == true) { "History did not load, notice: ${vm.notice.value}" }
        withContext(Dispatchers.Main) { vm.applyBackup(incoming) }
        check(disk.load() == mergeBackup(before, addition))
        check(vm.state.value.current.id == pending.id && vm.state.value.draft.text == "fresh draft")
        check(runCatching { withContext(Dispatchers.Main) { vm.applyBackup(incoming) } }.isFailure)
        check(disk.load() == mergeBackup(before, addition))
        withContext(Dispatchers.Main) { vm.undoBackup() }
        check(disk.load() == before)
        check(vm.state.value.history.single().id == existingId && vm.state.value.draft == Draft())
        withContext(Dispatchers.Main) {
            vm.applyBackup(incoming)
            vm.setDraft("edited after import")
            check(runCatching { vm.undoBackup() }.isFailure)
            check(vm.state.value.draft.text == "edited after import")
        }
        check(withTimeoutOrNull(20_000) {
            // On API 28 AtomicFile.openRead() may roll back an in-flight write. Observe bytes without mutating them.
            while (runCatching { decodeWorkspace(org.json.JSONObject(disk.path.readText())) }
                    .getOrNull()?.drafts?.get(pending.id)?.text != "edited after import") delay(10)
            true
        } == true) { "Updated draft did not reach disk, notice: ${vm.notice.value}" }
    }
}
