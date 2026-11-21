package io.hydrion.hub.model

import java.io.*
import java.security.DigestInputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import org.json.JSONObject

private const val MiB = 1024 * 1024L
private const val MaxBlob = 32 * MiB
private const val MaxData = 256 * MiB
private const val MaxMetadata = 8 * MiB
private const val MaxBlobs = 1024
private val digestPattern = Regex("[0-9a-f]{64}")
private val uuid = Regex("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}")
private val demoImage = Regex("${uuid.pattern}-[0-9]+")
internal class UnreadableOriginals(val names: Set<String>) : IOException()

internal fun InputStream.copyLimited(out: OutputStream, limit: Long): Long {
    val buffer = ByteArray(32 * 1024)
    var total = 0L
    while (true) {
        val count = read(buffer)
        if (count == -1) return total
        total += count
        require(total <= limit)
        out.write(buffer, 0, count)
    }
}

private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).asHex()
private fun ByteArray.asHex() = joinToString("") { "%02x".format(java.util.Locale.ROOT, it.toInt() and 255) }

private fun parseObject(bytes: ByteArray): JSONObject {
    val text = bytes.toString(Charsets.UTF_8)
    var depth = 0
    var quoted = false
    var escape = false
    for (c in text) {
        if (escape) escape = false
        else if (quoted && c == '\\') escape = true
        else if (c == '"') quoted = !quoted
        else if (!quoted) when (c) {
            '{', '[' -> require(++depth <= 32)
            '}', ']' -> require(--depth >= 0)
        }
    }
    require(depth == 0 && !quoted)
    return JSONObject(text)
}

private fun SavedWorkspace.transform(attachment: (Attachment) -> Attachment, image: (String) -> String): SavedWorkspace {
    // Project explicit portable fields. New fields in the internal history format require an opt-in here.
    fun route(r: RemoteModel?) = r?.let { RemoteModel(it.providerId, it.modelId) }
    fun portableAttachment(a: Attachment) = attachment(Attachment(a.id, a.name, a.uri, a.imageKey, a.mimeType, a.width, a.height))
    fun message(m: Message) = Message(
        id = m.id, role = m.role, text = m.text, createdAt = m.createdAt, model = m.model,
        status = if (m.status.isActive) Generation.Stopped else m.status,
        attachments = m.attachments.map(::portableAttachment), prompt = m.prompt, thought = m.thought,
        tool = m.tool, imageKeys = m.imageKeys.map(image), replyTo = m.replyTo, remoteModel = route(m.remoteModel),
        imageInvocation = m.imageInvocation?.let { ImageInvocation(it.model, emptyMap(), route(it.remoteModel)) },
        totalTokens = m.totalTokens, usage = m.usage?.let {
            TokenUsage(it.inputTokens, it.outputTokens, it.totalTokens, it.cachedInputTokens, it.reasoningTokens)
        },
    )
    fun conversation(c: Conversation) = Conversation(id = c.id, title = c.title, mode = c.mode,
        pinned = c.pinned, updatedAt = c.updatedAt, messages = c.messages.map(::message), assistantId = c.assistantId)
    return SavedWorkspace(history.map(::conversation), drafts.mapValues { (_, d) ->
        Draft(d.text, d.attachments.map(::portableAttachment), d.search, d.imageTool)
    }, unsent?.let(::conversation), assistants.map { LocalAssistant(it.id, it.name) }, selectedAssistant,
        otherUnsent.map(::conversation), lastThreads.toMap())
}

private fun stableId(value: String) = value.isNotBlank() && value.length <= 256 &&
    value.none { it.isISOControl() || it == '/' || it == '\\' }

private fun SavedWorkspace.checkIds() {
    val owners = assistants.map { it.id }.toSet()
    require(owners.size == assistants.size && LocalAssistant.Default.id in owners && selectedAssistant in owners)
    require(assistants.all { stableId(it.id) && LocalAssistant.validName(it.name) })
    val conversations = history + pending
    require(conversations.all { it.assistantId in owners })
    require(pending.map { it.assistantId }.distinct().size == pending.size)
    require(unsent == null || unsent.assistantId == selectedAssistant)
    require(otherUnsent.none { it.assistantId == selectedAssistant })
    val historyOwners = history.associate { it.id to it.assistantId }
    require(lastThreads.all { (assistant, thread) -> historyOwners[thread] == assistant })
    val ids = conversations.map { it.id }
    require(ids.all(::stableId) && ids.distinct().size == ids.size && ids.containsAll(drafts.keys))
    require(pending.all { it.messages.isEmpty() && it.id in drafts })
    fun checkAttachments(items: List<Attachment>) {
        require(items.distinctBy { it.id }.size == items.size)
        require(items.none { it.id.isBlank() || (it.imageKey == null && it.uri == null) })
    }
    drafts.values.forEach { checkAttachments(it.attachments) }
    for (c in conversations) {
        val messages = c.messages.map { it.id }
        val keys = messages.toSet()
        require(messages.all(::stableId) && keys.size == messages.size)
        for (message in c.messages) {
            require(message.replyTo == null || message.replyTo in keys)
            require(message.imageKeys.distinct().size == message.imageKeys.size)
            checkAttachments(message.attachments)
        }
    }
}

internal data class InspectedChatArchive(
    val workspace: SavedWorkspace, val blobs: Map<String, File>, val generated: Map<String, String>,
) {
    fun restored(pickedUri: (String) -> String, imageKey: (String) -> String): SavedWorkspace =
        workspace.transform({ a -> a.copy(uri = a.uri?.let { pickedUri(it.removePrefix("blob:")) },
            imageKey = a.imageKey?.let(imageKey)) }, imageKey)
}

internal object ChatArchive {
    /** All input is copied into a new private staging directory. Caller owns it through preview and commit. */
    suspend fun export(
        work: File, source: SavedWorkspace, openPicked: (String) -> InputStream,
        copyImage: suspend (String, OutputStream) -> Unit,
    ): File {
        source.checkIds()
        val blobs = linkedMapOf<String, File>()
        val picked = mutableMapOf<String, String>()
        val generated = linkedMapOf<String, String>()
        val unavailable = linkedSetOf<String>()
        var total = 0L
        fun record(temp: File): String {
            val sha = MessageDigest.getInstance("SHA-256")
            temp.inputStream().use { input ->
                val buffer = ByteArray(32 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    sha.update(buffer, 0, count)
                }
            }
            val id = sha.digest().asHex()
            if (id !in blobs) {
                total += temp.length()
                require(total <= MaxData && blobs.size < MaxBlobs)
                blobs[id] = temp
            } else temp.delete()
            return id
        }
        val attachments = (source.history.asSequence().flatMap { c -> c.messages.asSequence().flatMap { it.attachments.asSequence() } } +
            source.drafts.values.asSequence().flatMap { it.attachments.asSequence() }).toList()
        for (a in attachments.distinctBy { it.uri }) {
            val uri = a.uri ?: continue
            val temp = File.createTempFile("picked-", null, work)
            try {
                require(uri.startsWith("content://"))
                openPicked(uri).use { input -> temp.outputStream().use { input.copyLimited(it, MaxBlob) } }
            } catch (cancelled: java.util.concurrent.CancellationException) { throw cancelled }
            catch (_: Exception) { unavailable += a.name; temp.delete(); continue }
            picked[uri] = record(temp)
        }
        val images = (source.history.flatMap { c -> c.messages.flatMap { it.imageKeys } } +
            attachments.mapNotNull { it.imageKey }).distinct()
        for (key in images) {
            if (demoImage.matches(key)) continue
            val temp = File.createTempFile("png-", null, work)
            try {
                GeneratedImageStore.dimensions(key)
                temp.outputStream().use { copyImage(key, it) }
                require(temp.length() in 1..MaxBlob)
            } catch (cancelled: java.util.concurrent.CancellationException) { throw cancelled }
            catch (_: Exception) { unavailable += key; temp.delete(); continue }
            generated[key] = record(temp)
        }
        if (unavailable.isNotEmpty()) throw UnreadableOriginals(unavailable)
        val clean = source.transform({ a ->
            require(a.uri != null || a.imageKey != null)
            val id = if (stableId(a.id)) a.id else UUID.nameUUIDFromBytes(a.id.toByteArray()).toString()
            a.copy(id = id, uri = a.uri?.let { "blob:" + picked.getValue(it) })
        }, { it })
        val state = encodeWorkspace(clean).toString().toByteArray()
        require(state.size <= MaxMetadata)
        val version = if (clean.assistants == listOf(LocalAssistant.Default)) 1 else 2
        val manifest = JSONObject().put("format", "hydrion.chat").put("version", version)
            .put("workspaceSha256", digest(state)).put("generated", JSONObject(generated))
        val zip = File(work, "chats.zip")
        ZipOutputStream(zip.outputStream().buffered()).use { output ->
            fun add(name: String, write: () -> Unit) {
                output.putNextEntry(ZipEntry(name)); write(); output.closeEntry()
            }
            add("manifest.json") { output.write(manifest.toString().toByteArray()) }
            add("workspace.json") { output.write(state) }
            blobs.forEach { (key, temp) -> add("blobs/$key") { temp.inputStream().use { it.copyTo(output) } } }
        }
        return zip
    }

    fun inspect(work: File, input: InputStream): InspectedChatArchive {
        val zip = File(work, "input.zip")
        zip.outputStream().use { input.copyLimited(it, MaxData + 2 * MaxMetadata) }
        return ZipFile(zip).use { file ->
            require(file.size() in 2..(MaxBlobs + 2))
            val entries = file.entries().toList()
            require(entries.none { it.isDirectory } && entries.map { it.name }.distinct().size == entries.size)
            fun read(name: String): ByteArray {
                val entry = requireNotNull(file.getEntry(name))
                require(entry.size in 2..MaxMetadata)
                return file.getInputStream(entry).use { stream ->
                    ByteArrayOutputStream().also { stream.copyLimited(it, entry.size) }.toByteArray()
                }.also { require(it.size.toLong() == entry.size) }
            }
            val manifest = parseObject(read("manifest.json"))
            require(manifest.getString("format") == "hydrion.chat" && manifest.get("version") in listOf(1, 2))
            val state = read("workspace.json")
            require(digest(state) == manifest.getString("workspaceSha256"))
            val json = parseObject(state)
            json.getJSONArray("history")
            json.getJSONObject("drafts")
            val workspace = decodeWorkspace(json)
            workspace.checkIds()
            require(manifest.getInt("version") == 2 || workspace.assistants == listOf(LocalAssistant.Default))
            val imageMap = manifest.getJSONObject("generated")
            val generated = imageMap.keys().asSequence().associateWith { id ->
                GeneratedImageStore.dimensions(id)
                imageMap.getString(id).also { require(digestPattern.matches(it)) }
            }
            val usedImages = mutableSetOf<String>()
            val usedBlobs = mutableSetOf<String>()
            fun image(id: String): String {
                if (!demoImage.matches(id)) {
                    usedBlobs += generated.getValue(id)
                    usedImages += id
                }
                return id
            }
            val clean = workspace.transform({ a ->
                require(stableId(a.id) && (a.uri != null || a.imageKey != null))
                a.uri?.let { uri ->
                    require(uri.startsWith("blob:"))
                    usedBlobs += uri.substring(5).also { require(digestPattern.matches(it)) }
                }
                a.imageKey?.let(::image)
                a
            }, ::image)
            require(usedImages == generated.keys)
            val expected = usedBlobs.mapTo(mutableSetOf()) { "blobs/$it" } + setOf("manifest.json", "workspace.json")
            val actual = entries.mapTo(mutableSetOf()) { it.name }
            val missing = expected - actual
            if (missing.isNotEmpty()) {
                val attachments = clean.history.flatMap { c -> c.messages.flatMap { it.attachments } } +
                    clean.drafts.values.flatMap { it.attachments }
                val names = attachments.filter { a -> a.uri?.let { "blobs/" + it.removePrefix("blob:") in missing } == true }
                    .mapTo(linkedSetOf()) { it.name }
                generated.filterValues { "blobs/$it" in missing }.keys.forEach(names::add)
                throw UnreadableOriginals(names)
            }
            require(actual == expected)
            var total = 0L
            val blobs = usedBlobs.associateWith { id ->
                val entry = file.getEntry("blobs/$id")
                require(entry.size in 0..MaxBlob)
                total += entry.size
                require(total <= MaxData)
                val target = File.createTempFile("blob-", null, work)
                val sha = MessageDigest.getInstance("SHA-256")
                file.getInputStream(entry).use { stream ->
                    target.outputStream().use { require(DigestInputStream(stream, sha).copyLimited(it, entry.size) == entry.size) }
                }
                require(sha.digest().asHex() == id)
                target
            }
            InspectedChatArchive(clean, blobs, generated)
        }
    }
}
