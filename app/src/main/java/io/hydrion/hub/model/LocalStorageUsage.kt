package io.hydrion.hub.model

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS

internal data class LocalStorageUsage(
    val history: Long, val images: Long, val imported: Long, val settings: Long,
    val cachedCopies: Long, val expiredCopies: Long,
)

internal class LocalStorageFiles(private val files: File, private val cache: File, private val preferences: File) {
    private fun File.regular() = Files.isRegularFile(toPath(), NOFOLLOW_LINKS)
    private fun File.children(): List<File> = if (Files.isDirectory(toPath(), NOFOLLOW_LINKS))
        (listFiles() ?: throw java.io.IOException("Directory unavailable")).toList() else emptyList()
    private fun File.bytes() = if (regular()) Files.size(toPath()) else 0L
    private fun copies() = File(cache, "copies").children().filter {
        it.name.startsWith("HydrionHub-") && it.name.endsWith(".png") && it.regular()
    }
    private fun File.expired(now: Long) = lastModified() in 1 until (now - 86_400_000L)

    fun usage(now: Long = System.currentTimeMillis()): LocalStorageUsage {
        val copies = copies()
        return LocalStorageUsage(
            File(files, "conversation-history-v1.json").bytes(),
            File(files, "generated-images").children().sumOf { dir -> dir.children().sumOf { it.bytes() } },
            File(files, "restored-attachments").children().sumOf { it.bytes() },
            listOf("message_style", "local_profile", "model_catalog_v1", "chat_dictation").sumOf {
                File(preferences, "$it.xml").bytes()
            },
            copies.sumOf { it.bytes() }, copies.filter { it.expired(now) }.sumOf { it.bytes() },
        )
    }

    fun clearExpired(now: Long = System.currentTimeMillis()) {
        copies().filter { it.expired(now) }.forEach { Files.deleteIfExists(it.toPath()) }
    }
}
