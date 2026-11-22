package io.hydrion.hub.model

import io.hydrion.hub.provider.*
import io.hydrion.hub.ui.messages.MessageStyle
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AppBackupTest {
    @get:Rule val temp = TemporaryFolder()
    private val route = RemoteModel("provider", "model")
    private val owner = LocalAssistant("owner", "Writer", "Group", "A short prompt", route, "W", 42)
    private val oldUri = "content://old.test/picked"
    private val picked = Attachment("att", "note.txt", oldUri, mimeType = "text/plain")
    private val safeProtocol = ChatProtocol.ChatCompletionsHttp
    private val model = ProviderModel(route.modelId, "Fixture", profile = ModelProfile(
        type = ModelType.Chat, externalId = "fixture-model", contextWindowTokens = 32768,
        headers = mapOf("Authorization" to "fake-http-secret"), requestBody = "fake-oauth-token"))
    private val provider = Provider(route.providerId, Mode.Chat, "Fixture provider", safeProtocol.label,
        "https://api.openai.com/v1", listOf(model), requestBody = "fake-api-key")
    private val settings = AppBackupSettings(ModelCatalog(listOf(provider,
        provider.copy(id = "custom", baseUrl = "https://invalid.test/fake-endpoint-token")),
        defaults = mapOf(Mode.Chat to route), active = mapOf(Mode.Chat to route),
        overrides = mapOf(route to mapOf("header" to "fake-signing-key")), perChatModel = false),
        MessageStyle(assistantBubble = true))
    private val before = SavedWorkspace(history = listOf(Conversation(title = "Existing chat")))

    private fun export(state: SavedWorkspace, config: AppBackupSettings? = settings): File = runBlocking {
        ChatArchive.write(temp.newFolder(), state, config, { uri ->
            assertEquals(oldUri, uri)
            "fixture attachment".byteInputStream()
        }, { _, _ -> error("unexpected image") })
    }

    private fun inspect(zip: File, application: Boolean = true) =
        zip.inputStream().use { ChatArchive.inspect(temp.newFolder(), it, application) }

    private fun readState(store: AppRestoreStore, legacy: File) =
        decodeWorkspace(JSONObject(store.history(legacy).readText()))

    @Test fun coldStartRestoresChatsAndProviderReferencesOnAnEmptyInstallation() {
        val user = Message(role = Role.User, text = "question", attachments = listOf(picked))
        val old = Message(role = Role.Assistant, text = "first", replyTo = user.id, remoteModel = route,
            parameters = mapOf(CHAT_PROTOCOL_PARAMETER to safeProtocol.name, "api_key" to "fake-message-key"))
        val chat = Conversation(title = "Original", assistantId = owner.id, messages = listOf(user, old),
            modelRoutes = mapOf(Mode.Chat to route), contextStart = user.id)
            .appendVersion(old, old.copy(text = "second"))
        val branch = chat.copyThrough(0, "Branch", 1234, keepVersions = true)
        val pending = Conversation(assistantId = owner.id)
        val source = SavedWorkspace(listOf(chat, branch), mapOf(chat.id to Draft("edit"),
            pending.id to Draft("unsent", listOf(picked))), pending, listOf(LocalAssistant.Default, owner),
            owner.id, lastThreads = mapOf(owner.id to chat.id))
        val zip = export(source)
        ZipFile(zip).use { archive ->
            val data = archive.entries().toList().joinToString { archive.getInputStream(it).use { s -> s.reader().readText() } }
            for (excluded in listOf("fake-http-secret", "fake-oauth-token", "fake-api-key", "fake-signing-key",
                    "fake-message-key", "fake-endpoint-token", oldUri)) assertFalse(excluded, data.contains(excluded))
        }
        val preview = inspect(zip)
        val fresh = temp.newFolder()
        val files = temp.newFolder()
        val firstProcess = AppRestoreStore(fresh)
        val incoming = preview.restored({ hash ->
            val dest = File(files, hash)
            installBackupFile(preview.blobs.getValue(hash), dest)
            "content://fresh.test/" + dest.name
        }, { error("unexpected image") })
        val stage = firstProcess.stage()
        firstProcess.prepare(stage, incoming, requireNotNull(preview.settings), export(SavedWorkspace()))
        firstProcess.publish(stage)
        assertTrue(firstProcess.pendingRestart())
        assertFalse(firstProcess.history(files).exists())

        val restarted = AppRestoreStore(fresh)
        val saved = readState(restarted, files)
        assertEquals(incoming, saved)
        assertEquals(listOf("Original", "Branch"), saved.history.map { it.title })
        assertEquals(owner, saved.assistants.single { it.id == owner.id })
        assertEquals("edit", saved.drafts.getValue(chat.id).text)
        val draft = saved.drafts.getValue(saved.unsent!!.id)
        assertEquals("unsent", draft.text)
        assertEquals("fixture attachment", File(files, draft.attachments.single().uri!!.substringAfterLast('/')).readText())
        val thread = saved.history.first()
        assertEquals(user.id, thread.contextStart)
        assertEquals(chat.id, saved.lastThreads[owner.id])
        assertEquals("first", thread.withVersion(old.id, 0).messages.last().text)
        assertEquals("second", thread.messages.last().text)
        assertEquals(mapOf(CHAT_PROTOCOL_PARAMETER to safeProtocol.name), thread.messages.last().parameters)
        val config = requireNotNull(restarted.initialSettings())
        assertEquals(settings.appearance, config.appearance)
        assertFalse(config.catalog.perChatModel)
        assertEquals(route, thread.modelRoutes!![Mode.Chat])
        assertEquals(owner.chatModel, config.catalog.defaultEntry(Mode.Chat)?.route)
        assertEquals(32768, config.catalog.defaultModel(Mode.Chat)!!.contextWindowTokens)
        assertFalse(config.catalog.providers.last().enabled)
        assertEquals("", config.catalog.providers.last().baseUrl)
        assertEquals(SavedWorkspace(), inspect(requireNotNull(restarted.previousBackup)).workspace)
    }

    @Test fun injectedFailureOrProcessDeathBeforePublicationKeepsPriorGenerationIntact() {
        val root = temp.newFolder()
        val files = temp.newFolder()
        val original = AppRestoreStore(root)
        val first = original.stage()
        val initial = settings.copy(appearance = MessageStyle())
        original.prepare(first, before, initial, export(SavedWorkspace()))
        original.publish(first)
        val existing = AppRestoreStore(root)
        class SimulatedDeath : Error()
        for (failure in listOf(IOException("test switch failure"), SimulatedDeath())) {
            val failing = AppRestoreStore(root) { _, _ -> throw failure }
            val next = failing.stage()
            failing.prepare(next, SavedWorkspace(), settings, export(before, initial))
            assertSame(failure, assertThrows(Throwable::class.java) { failing.publish(next) })
            val fresh = AppRestoreStore(root)
            assertEquals(existing.generation, fresh.generation)
            assertEquals(before, readState(fresh, files))
            assertEquals(initial.appearance, fresh.initialSettings()!!.appearance)
        }
        val next = existing.stage()
        existing.prepare(next, SavedWorkspace(), settings, export(before, initial))
        existing.publish(next)
        val replaced = AppRestoreStore(root)
        assertEquals(SavedWorkspace(), readState(replaced, files))
        assertEquals(settings.appearance, replaced.initialSettings()!!.appearance)
        val rollback = inspect(requireNotNull(replaced.previousBackup))
        assertEquals(before, rollback.workspace)
        assertEquals(initial.appearance, rollback.settings!!.appearance)
        existing.cancelPending()
        assertEquals(existing.generation, AppRestoreStore(root).generation)
    }

    @Test fun invalidArchivesAreRejectedBeforeAnyChangeToLiveState() {
        val root = temp.newFolder()
        val files = temp.newFolder()
        val original = AppRestoreStore(root)
        original.history(files).writeText(encodeWorkspace(before).toString())
        val zip = export(SavedWorkspace())
        val valid = ZipFile(zip).use { archive -> archive.entries().toList().associate {
            it.name to archive.getInputStream(it).use { s -> s.readBytes() }
        } }
        fun packed(entries: Map<String, ByteArray>) = ByteArrayOutputStream().also { bytes ->
            ZipOutputStream(bytes).use { out -> for ((name, data) in entries) {
                out.putNextEntry(ZipEntry(name)); out.write(data); out.closeEntry()
            } }
        }.toByteArray()
        val duplicate = packed(valid + ("settingz.json" to byteArrayOf(1)))
        val alias = "settingz.json".toByteArray()
        for (i in 0..duplicate.size - alias.size) {
            if (alias.indices.all { duplicate[i + it] == alias[it] }) duplicate[i + 7] = 's'.code.toByte()
        }
        val future = JSONObject(String(valid.getValue("manifest.json"))).put("version", 2)
        val variations = listOf(
            packed(valid + ("settings.json" to "{}".toByteArray())),
            packed(valid + ("manifest.json" to future.toString().toByteArray())),
            packed(valid + ("../outside" to byteArrayOf(1))),
            packed(valid + ("workspace.json" to ByteArray(8 * 1024 * 1024 + 1))),
            duplicate, zip.readBytes().copyOf(20))
        for (bad in variations) {
            assertThrows(Exception::class.java) { ChatArchive.inspect(temp.newFolder(), bad.inputStream(), true) }
            assertFalse(original.pendingRestart())
            assertEquals(before, readState(AppRestoreStore(root), files))
        }
    }

    @Test fun deletedModelReferencesAreReportedAndBuiltInModelLevelsSurvive() {
        val deleted = RemoteModel("removed", "historical")
        val state = SavedWorkspace(assistants = listOf(LocalAssistant.Default, owner.copy(chatModel = deleted)))
        val config = AppBackupSettings(demoCatalog().withDeepSeekPreset(), MessageStyle())
        val inspected = inspect(export(state, config))
        assertEquals(1, inspected.missingModelReferences())
        assertEquals(deleted, inspected.workspace.assistants.last().chatModel)
        val restored = inspected.settings!!.catalog.providers.single { it.id == deepSeekProvider.id }
        assertEquals(deepSeekProvider.models.map { it.configureCapabilities() }, restored.models.map { it.configureCapabilities() })
    }

    @Test fun chatZipAndApplicationZipHaveSeparateReadersAndRestoreScopes() {
        val state = SavedWorkspace(assistants = listOf(LocalAssistant.Default, owner))
        val legacy = export(state, null)
        val chatPreview = inspect(legacy, false)
        assertEquals(2, chatPreview.formatVersion)
        assertEquals("", chatPreview.workspace.assistants.last().systemPrompt)
        assertFalse(assertThrows(ArchiveScopeMismatch::class.java) { inspect(legacy) }.isApplicationArchive)
        val app = export(state)
        val appPreview = inspect(app)
        assertEquals(1, appPreview.formatVersion)
        assertEquals(owner, appPreview.workspace.assistants.last())
        assertTrue(assertThrows(ArchiveScopeMismatch::class.java) { inspect(app, false) }.isApplicationArchive)
    }
}
