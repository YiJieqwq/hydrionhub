package io.hydrion.hub.model

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.channels.Channels
import java.nio.channels.FileChannel
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.NoSuchFileException
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.READ
import java.nio.file.StandardOpenOption.WRITE
import java.util.UUID
import java.util.zip.CRC32
import java.util.zip.DataFormatException
import java.util.zip.Inflater
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

internal class GeneratedImageStore(
    private val root: File,
    private val canDecodePng: (ByteArray, Int) -> Boolean = ::androidCanDecodePng,
) {
    constructor(context: Context) : this(File(context.filesDir, "generated-images"))

    companion object {
        private const val PREFIX = "generated:"
        private const val MAX_IMAGE_BYTES = 32 * 1024 * 1024
        private const val MAX_BATCH_BYTES = 64L * 1024 * 1024
        private const val PRUNE_GRACE_MS = 24L * 60 * 60 * 1000
        private const val UUID_PATTERN = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"
        private val batchName = Regex(UUID_PATTERN)
        private val imageKey = Regex("${PREFIX}($UUID_PATTERN):([0-9]):([1-9][0-9]{0,3})x([1-9][0-9]{0,3})")
        private val imageName = Regex("([0-9])-([1-9][0-9]{0,3})x([1-9][0-9]{0,3})\\.png")

        fun isStored(key: String) = key.startsWith(PREFIX)

        private fun parts(key: String): MatchResult = imageKey.matchEntire(key) ?: throw IOException("生成图片标识无效。")

        fun dimensions(key: String): Pair<Int, Int> {
            val groups = parts(key).groupValues
            return checkedSize(groups[3].toInt(), groups[4].toInt())
        }

        private fun checkedSize(width: Int, height: Int): Pair<Int, Int> {
            require(width in 1..4096 && height in 1..4096 && width.toLong() * height <= 8_388_608) {
                "生成图片的尺寸超出本机支持范围。"
            }
            return width to height
        }
    }

    /** All files become readable together. If dispatching the result back is cancelled, remove the committed batch too. */
    suspend fun saveBatch(images: List<ByteArray>): List<String> {
        require(images.size in 1..10 && images.all { it.size in 1..MAX_IMAGE_BYTES } &&
            images.sumOf { it.size.toLong() } <= MAX_BATCH_BYTES) { "返回的图片数量或大小超出本机支持范围。" }
        val id = UUID.randomUUID().toString()
        val staging = File(root, ".pending-$id")
        val batch = File(root, id)
        var staged = false
        var published = false
        try {
            return withContext(Dispatchers.IO) {
                try {
                    Files.createDirectory(root.toPath())
                } catch (_: FileAlreadyExistsException) {
                    // An existing store must be a real directory.
                }
                requireDirectory(root)
                Files.createDirectory(staging.toPath())
                staged = true
                val keys = images.mapIndexed { index, bytes ->
                    currentCoroutineContext().ensureActive()
                    val (width, height) = validatePng(bytes)
                    val name = "$index-${width}x${height}.png"
                    requireDirectory(root)
                    requireDirectory(staging)
                    FileChannel.open(File(staging, name).toPath(), CREATE_NEW, WRITE, NOFOLLOW_LINKS).use { out ->
                        val data = ByteBuffer.wrap(bytes)
                        while (data.hasRemaining()) {
                            currentCoroutineContext().ensureActive()
                            out.write(data)
                        }
                        out.force(true)
                    }
                    "$PREFIX$id:$index:${width}x$height"
                }
                currentCoroutineContext().ensureActive()
                requireDirectory(root)
                requireDirectory(staging)
                if (Files.exists(batch.toPath(), NOFOLLOW_LINKS)) throw IOException("图片批次标识冲突。")
                Files.move(staging.toPath(), batch.toPath(), StandardCopyOption.ATOMIC_MOVE)
                published = true
                keys
            }
        } catch (failure: Throwable) {
            withContext(NonCancellable + Dispatchers.IO) {
                if (staged && !published) cleanBatch(staging, emptySet())
                if (published) cleanBatch(batch, emptySet())
            }
            throw failure
        }
    }

    /** Discard known outputs when a completed store write could not be attached to the conversation. Call on IO. */
    fun discard(keys: List<String>) {
        for (key in keys) {
            if (!isStored(key)) continue
            val file = imageFile(key)
            val batch = file.parentFile ?: continue
            if (!isOwnedDirectory(batch) || !Files.isRegularFile(file.toPath(), NOFOLLOW_LINKS)) continue
            file.delete()
            if (isOwnedDirectory(batch)) batch.delete() // Only succeeds if empty.
        }
    }

    suspend fun pruneUnreferenced(referencedKeys: Set<String>) = withContext(Dispatchers.IO) {
        val cutoff = System.currentTimeMillis() - PRUNE_GRACE_MS
        if (!Files.isDirectory(root.toPath(), NOFOLLOW_LINKS)) return@withContext
        for (batch in root.listFiles().orEmpty()) {
            currentCoroutineContext().ensureActive()
            if (!isOwnedDirectory(batch)) continue
            val modified = try {
                Files.getLastModifiedTime(batch.toPath(), NOFOLLOW_LINKS).toMillis()
            } catch (_: NoSuchFileException) {
                continue
            }
            if (modified >= cutoff) continue
            cleanBatch(batch, if (batch.name.startsWith(".pending-")) emptySet() else referencedKeys)
        }
    }

    private fun requireDirectory(dir: File) {
        if (!Files.isDirectory(dir.toPath(), NOFOLLOW_LINKS)) throw IOException("生成图片目录无效。")
    }

    private fun isOwnedDirectory(dir: File): Boolean = dir.parentFile == root &&
        batchName.matches(dir.name.removePrefix(".pending-")) &&
        Files.isDirectory(root.toPath(), NOFOLLOW_LINKS) && Files.isDirectory(dir.toPath(), NOFOLLOW_LINKS)

    /** Only unlink the regular PNGs written by this store. Unknown entries keep the directory alive. */
    private fun cleanBatch(directory: File, keep: Set<String>) {
        if (!isOwnedDirectory(directory)) return
        val id = directory.name.removePrefix(".pending-")
        for (file in directory.listFiles().orEmpty()) {
            val groups = imageName.matchEntire(file.name)?.groupValues ?: continue
            if (!isOwnedDirectory(directory) || !Files.isRegularFile(file.toPath(), NOFOLLOW_LINKS)) continue
            val key = "$PREFIX$id:${groups[1]}:${groups[2]}x${groups[3]}"
            if (key !in keep) file.delete()
        }
        if (isOwnedDirectory(directory)) directory.delete()
    }

    private fun imageFile(key: String): File {
        val parts = parts(key).groupValues
        return File(File(root, parts[1]), "${parts[2]}-${parts[3]}x${parts[4]}.png")
    }

    private fun openImage(key: String): FileChannel {
        val file = imageFile(key)
        val error = "找不到生成的 PNG 图片或文件已损坏。"
        if (file.parentFile?.let(::isOwnedDirectory) != true || !Files.isRegularFile(file.toPath(), NOFOLLOW_LINKS)) {
            throw IOException(error)
        }
        val channel = FileChannel.open(file.toPath(), READ, NOFOLLOW_LINKS)
        try {
            if (channel.size() !in 45..MAX_IMAGE_BYTES.toLong()) throw IOException(error)
            // Check the dimensions of the opened file before allowing a bitmap allocation.
            val (width, height) = dimensions(key)
            val header = ByteBuffer.allocate(24)
            while (header.hasRemaining()) {
                if (channel.read(header) <= 0) throw IOException(error)
            }
            if (header.getInt(0) != 0x89504e47.toInt() || header.getInt(4) != 0x0d0a1a0a ||
                header.getInt(8) != 13 || header.getInt(12) != 0x49484452 ||
                header.getInt(16) != width || header.getInt(20) != height) {
                throw IOException(error)
            }
            channel.position(0)
        } catch (e: Throwable) {
            channel.close()
            throw e
        }
        return channel
    }

    /** Validate all archive PNGs during preview. Recheck on publication and never overwrite a live image. */
    suspend fun checkArchivePng(key: String, source: File) {
        require(source.length() in 45..MAX_IMAGE_BYTES.toLong())
        require(validatePng(source.readBytes()) == dimensions(key))
    }

    fun installArchivePng(key: String, source: File): Boolean {
        val dest = imageFile(key)
        val batch = requireNotNull(dest.parentFile)
        Files.createDirectories(root.toPath())
        requireDirectory(root)
        Files.createDirectories(batch.toPath())
        require(isOwnedDirectory(batch))
        if (Files.exists(dest.toPath(), NOFOLLOW_LINKS)) {
            require(Files.isRegularFile(dest.toPath(), NOFOLLOW_LINKS) && sameBackupBytes(source, dest))
            return false
        }
        installBackupFile(source, dest)
        return true
    }

    suspend fun requireAvailable(key: String) = withContext(Dispatchers.IO) { openImage(key).close() }

    suspend fun copyPng(key: String, output: OutputStream) = withContext(Dispatchers.IO) {
        Channels.newInputStream(openImage(key)).use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                currentCoroutineContext().ensureActive()
                val count = input.read(buffer)
                if (count < 0) break
                output.write(buffer, 0, count)
            }
        }
    }

    /** The bytes and dimensions were checked before publication. Decode only what the preview needs. Call on IO. */
    fun decodePreview(key: String, maxEdge: Int): Bitmap {
        require(maxEdge in 1..4096)
        val (width, height) = dimensions(key)
        val largest = maxOf(width, height)
        val options = BitmapFactory.Options().apply {
            inSampleSize = Integer.highestOneBit((largest / maxEdge).coerceAtLeast(1))
        }
        val bitmap = Channels.newInputStream(openImage(key)).use { BitmapFactory.decodeStream(it, null, options) }
            ?: throw IOException("无法解码生成的 PNG 图片。")
        if (maxOf(bitmap.width, bitmap.height) <= maxEdge) return bitmap
        val scale = maxEdge.toDouble() / largest
        return Bitmap.createScaledBitmap(bitmap, (width * scale).roundToInt().coerceAtLeast(1),
            (height * scale).roundToInt().coerceAtLeast(1), true).also { if (it !== bitmap) bitmap.recycle() }
    }

    /** Android's bitmap reader may accept a truncated PNG. Check all chunks and the full pixel stream before saving. */
    private suspend fun validatePng(bytes: ByteArray): Pair<Int, Int> {
        fun invalid(): Nothing = throw IOException("返回的 PNG 图片无效或不完整。")
        fun intAt(i: Int) = ((bytes[i].toInt() and 255) shl 24) or ((bytes[i+1].toInt() and 255) shl 16) or
            ((bytes[i+2].toInt() and 255) shl 8) or (bytes[i+3].toInt() and 255)
        if (bytes.size < 45 || intAt(0) != 0x89504e47.toInt() || intAt(4) != 0x0d0a1a0a ||
            intAt(8) != 13 || intAt(12) != 0x49484452) invalid()
        val size = checkedSize(intAt(16), intAt(20))
        val depth = bytes[24].toInt()
        val channels = when (bytes[25].toInt()) {
            0 -> if (depth in setOf(1, 2, 4, 8, 16)) 1 else invalid()
            2 -> if (depth == 8 || depth == 16) 3 else invalid()
            3 -> if (depth in setOf(1, 2, 4, 8)) 1 else invalid()
            4 -> if (depth == 8 || depth == 16) 2 else invalid()
            6 -> if (depth == 8 || depth == 16) 4 else invalid()
            else -> invalid()
        }
        if (bytes[26].toInt() != 0 || bytes[27].toInt() != 0) invalid()
        val passes = when (bytes[28].toInt()) {
            0 -> arrayOf(intArrayOf(0, 0, 1, 1))
            1 -> arrayOf(intArrayOf(0,0,8,8), intArrayOf(4,0,8,8), intArrayOf(0,4,4,8),
                intArrayOf(2,0,4,4), intArrayOf(0,2,2,4), intArrayOf(1,0,2,2), intArrayOf(0,1,1,2))
            else -> invalid()
        }
        var expected = 0L
        for ((x, y, dx, dy) in passes) {
            val w = (size.first - x + dx - 1) / dx
            val h = (size.second - y + dy - 1) / dy
            if (w > 0 && h > 0) expected += h * (1L + (w.toLong() * channels * depth + 7) / 8)
        }
        val inflater = Inflater()
        val buffer = ByteArray(32 * 1024)
        val crc = CRC32()
        var offset = 8
        var ended = false
        try {
            while (offset <= bytes.size - 12) {
                currentCoroutineContext().ensureActive()
                val length = intAt(offset)
                if (length < 0 || length > bytes.size - offset - 12) invalid()
                crc.reset()
                crc.update(bytes, offset + 4, length + 4)
                if (crc.value.toInt() != intAt(offset + 8 + length)) invalid()
                when (intAt(offset + 4)) {
                    0x49484452 -> if (offset != 8) invalid()
                    0x49444154 -> {
                        // The final IDAT may contain unused input after the zlib end marker.
                        if (!inflater.finished()) inflater.setInput(bytes, offset + 8, length)
                        while (!inflater.needsInput() && !inflater.finished()) {
                            currentCoroutineContext().ensureActive()
                            if (inflater.inflate(buffer) == 0 && !inflater.needsInput() && !inflater.finished()) invalid()
                            if (inflater.bytesWritten > expected) invalid()
                        }
                    }
                    0x49454e44 -> {
                        if (length != 0 || offset + 12 != bytes.size || !inflater.finished() || inflater.bytesWritten != expected) invalid()
                        ended = true
                    }
                }
                offset += length + 12
            }
        } catch (_: DataFormatException) {
            invalid()
        } finally {
            inflater.end()
        }
        if (!ended) invalid()
        val sampleSize = Integer.highestOneBit((maxOf(size.first, size.second) / 128).coerceAtLeast(1))
        if (!canDecodePng(bytes, sampleSize)) invalid()
        return size
    }
}

private fun androidCanDecodePng(bytes: ByteArray, sampleSize: Int): Boolean {
    val options = BitmapFactory.Options().apply { inSampleSize = sampleSize }
    val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: return false
    decoded.recycle()
    return true
}
