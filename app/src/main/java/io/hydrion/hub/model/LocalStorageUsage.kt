package io.hydrion.hub.model

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS

internal data class LocalStorageUsage(
    val history: Long, val images: Long, val imported: Long, val settings: Long,
    val cachedCopies: Long, val expiredCopies: Long, val rollback: Long,
)

internal class LocalStorageFiles(private val files: File, private val cache: File, private val preferences: File,
    private val noBackup: File) {
    private fun File.regular() = Files.isRegularFile(toPath(), NOFOLLOW_LINKS)
    private fun File.children(): List<File> = if (Files.isDirectory(toPath(), NOFOLLOW_LINKS))
        (listFiles() ?: throw java.io.IOException("Directory unavailable")).toList() else emptyList()
    private fun File.bytes() = if (regular()) Files.size(toPath()) else 0L
    private fun copies() = File(cache, "copies").children().filter {
        it.name.startsWith("HydrionHub-") && it.name.endsWith(".png") && it.regular()
    }
    private fun File.expired(now: Long) = lastModified() in 1 until (now - 86_400_000L)

    fun usage(now: Long = System.currentTimeMillis(), protectedNames: Set<String> = emptySet()): LocalStorageUsage {
        val copies = copies()
        return LocalStorageUsage(
            File(files, "conversation-history-v1.json").bytes(),
            File(files, "generated-images").children().sumOf { dir -> dir.children().sumOf { it.bytes() } },
            File(files, "restored-attachments").children().sumOf { it.bytes() },
            listOf("message_style", "local_profile", "model_catalog_v1", "chat_dictation").sumOf {
                File(preferences, "$it.xml").bytes()
            },
            copies.sumOf { it.bytes() }, copies.filter { it.expired(now) && it.name !in protectedNames }.sumOf { it.bytes() },
            File(noBackup, "chat-import-undo.json").bytes(),
        )
    }

    fun clearExpired(now: Long = System.currentTimeMillis(), protectedNames: Set<String> = emptySet()) {
        copies().filter { it.expired(now) && it.name !in protectedNames }.forEach { Files.deleteIfExists(it.toPath()) }
    }
}

internal fun SavedWorkspace.clipboardOriginals(authority: String): Set<String> =
    (history.asSequence().flatMap { it.allStoredMessages() }.flatMap { it.attachments.asSequence() } +
        drafts.values.asSequence().flatMap { it.attachments.asSequence() })
        .mapNotNull { it.uri?.let { raw -> runCatching { java.net.URI(raw) }.getOrNull() } }
        .filter { it.scheme == "content" && it.authority == authority && it.path?.startsWith("/generated/") == true }
        .map { it.path.substringAfterLast('/') }.toSet()
