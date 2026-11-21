package io.hydrion.hub.runtime

import java.io.InputStream
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible

internal data class UbuntuBaseArchive(val arch: String, val androidAbi: String, val digest: String) {
    val filename get() = "ubuntu-base-26.04.1-base-$arch.tar.gz"
}

// Canonical's published SHA256SUMS for the 26.04.1 point release. These identify compressed
// bytes only; they do not grant execution or certify a provisioned Android installation.
// https://cdimage.ubuntu.com/ubuntu-base/releases/26.04.1/release/SHA256SUMS
internal val ubuntuBase260401 = listOf(
    UbuntuBaseArchive("arm64", "arm64-v8a", "5a1906794ced63a71a8119c3f211ef5f0bbe0a243001b4bbd41fdf80c5b219fd"),
    UbuntuBaseArchive("amd64", "x86_64", "a496a960472ce474a59590b8987d3a1135d3cbef1991f3b1abe8cacfea8bf85a"),
)

internal sealed interface ArchiveMatch {
    data class Published(val image: UbuntuBaseArchive, val matchesDeviceAbi: Boolean) : ArchiveMatch
    data object Unknown : ArchiveMatch
    data object TooLarge : ArchiveMatch
}

internal suspend fun inspectUbuntuBase(
    open: () -> InputStream, supportedAbis: List<String>, candidates: List<UbuntuBaseArchive> = ubuntuBase260401,
    byteLimit: Long = 64L * 1024 * 1024,
): ArchiveMatch = runInterruptible(Dispatchers.IO) {
    val hash = MessageDigest.getInstance("SHA-256")
    open().use { stream ->
        var total = 0L
        val buffer = ByteArray(32 * 1024)
        while (true) {
            if (Thread.currentThread().isInterrupted) throw InterruptedException()
            val n = stream.read(buffer)
            if (n < 0) break
            check(n > 0)
            total += n
            if (total > byteLimit) return@runInterruptible ArchiveMatch.TooLarge
            hash.update(buffer, 0, n)
        }
    }
    val digest = hash.digest().joinToString("") { "%02x".format(it) }
    val image = candidates.firstOrNull { it.digest == digest } ?: return@runInterruptible ArchiveMatch.Unknown
    ArchiveMatch.Published(image, image.androidAbi in supportedAbis)
}
