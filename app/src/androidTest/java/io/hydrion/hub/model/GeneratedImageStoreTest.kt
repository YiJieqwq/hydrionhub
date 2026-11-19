package io.hydrion.hub.model

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.hydrion.hub.provider.Provider
import io.hydrion.hub.provider.responsesImageSources
import io.hydrion.hub.ui.messages.savePicture
import io.hydrion.hub.ui.messages.writeDemoPng
import io.hydrion.hub.ui.messages.writePng
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.zip.CRC32
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GeneratedImageStoreTest {
    private lateinit var directory: File
    private lateinit var context: Context
    private lateinit var store: GeneratedImageStore
    private val provider = Provider("test", Mode.Chat, "test", "OpenAI Responses", "https://fixture.invalid", emptyList())

    @Before fun setUp() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        directory = Files.createTempDirectory(app.cacheDir.toPath(), "generated-test-").toFile()
        val files = File(directory, "files").apply { check(mkdir()) }
        val cache = File(directory, "cache").apply { check(mkdir()) }
        context = object : ContextWrapper(app) {
            override fun getFilesDir() = files
            override fun getCacheDir() = cache
        }
        store = GeneratedImageStore(context)
    }

    @After fun tearDown() {
        Files.walk(directory.toPath()).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
        }
    }

    private fun png(width: Int, height: Int, color: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        return try {
            bitmap.eraseColor(color)
            ByteArrayOutputStream().use {
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
                it.toByteArray()
            }
        } finally {
            bitmap.recycle()
        }
    }

    private suspend fun original(key: String): ByteArray =
        ByteArrayOutputStream().use { out ->
            store.copyPng(key, out)
            out.toByteArray()
        }

    private suspend fun assertRejected(block: suspend () -> Unit) {
        try {
            block()
        } catch (_: IOException) {
            return
        } catch (_: IllegalArgumentException) {
            return
        }
        fail("Expected invalid or missing generated image to be rejected")
    }

    @Test fun batchRetainsOriginalPngBytesAcrossPreviewExportAndReferenceUpload() = runBlocking(Dispatchers.IO) {
        val landscape = png(160, 80, Color.BLUE)
        val portrait = png(24, 48, Color.RED)
        val keys = store.saveBatch(listOf(landscape, portrait))
        assertEquals(2, keys.distinct().size)
        assertTrue(keys.all { GeneratedImageStore.isStored(it) })
        assertEquals(160 to 80, GeneratedImageStore.dimensions(keys[0]))
        assertEquals(24 to 48, GeneratedImageStore.dimensions(keys[1]))
        store.requireAvailable(keys[0])
        assertArrayEquals(landscape, original(keys[0]))
        assertArrayEquals(portrait, original(keys[1]))

        val preview = store.decodePreview(keys[0], maxEdge = 32)
        try {
            assertEquals(32, preview.width)
            assertEquals(16, preview.height)
            assertEquals(Color.BLUE, preview.getPixel(8, 8))
        } finally {
            preview.recycle()
        }
        val exported = File(directory, "saved.png")
        savePicture(context, Uri.fromFile(exported), keys[0], 1, 1)
        assertArrayEquals(landscape, exported.readBytes())
        val reference = Attachment("ref", "reference", imageKey = keys[1], width = 1, height = 1)
        val source = context.responsesImageSources(listOf(reference), provider).getValue(reference)
        assertTrue(source.startsWith("data:image/png;base64,"))
        assertArrayEquals(portrait, Base64.decode(source.substringAfter(','), Base64.NO_WRAP))
    }

    @Test fun acceptsOneBitIndexedAdam7WithTwoIdatChunks() = runBlocking(Dispatchers.IO) {
        // ImageMagick 9×11 two-color fixture, losslessly split across two IDAT chunks.
        val data = Base64.decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAkAAAALAQMAAAHgkAXOAAAABlBMVEX/AAAAAP9sof2O" +
                "AAAAEUlEQVQI12NgYHBgYGBoYEhgALFAbFTuWqsAAAARSURBVAsgBAEDKAQC/gY4" +
                "AgBopwUNvNUQlgAAAABJRU5ErkJggg==",
            Base64.DEFAULT
        )
        val key = store.saveBatch(listOf(data)).single()
        assertEquals(9 to 11, GeneratedImageStore.dimensions(key))
        assertArrayEquals(data, original(key))
        val bitmap = store.decodePreview(key, 32)
        try {
            assertEquals(Color.RED, bitmap.getPixel(0, 0))
            assertEquals(Color.BLUE, bitmap.getPixel(8, 10))
        } finally {
            bitmap.recycle()
        }
    }

    @Test fun unavailableGeneratedReferencesCannotRenderOrExportAnExampleOrOverwriteADocument() = runBlocking(Dispatchers.IO) {
        val removed = store.saveBatch(listOf(png(16, 16, Color.GRAY))).single()
        store.discard(listOf(removed))
        val destination = File(directory, "existing.png")
        val previous = png(8, 4, Color.GREEN)
        destination.writeBytes(previous)
        for (key in listOf(removed, "generated:invalid")) {
            assertTrue(GeneratedImageStore.isStored(key))
            assertRejected { store.requireAvailable(key) }
            assertRejected { store.decodePreview(key, 32).recycle() }
            assertRejected { savePicture(context, Uri.fromFile(destination), key, 16, 16) }
            assertArrayEquals(previous, destination.readBytes())
            val bytes = ByteArrayOutputStream()
            assertRejected { writePng(context, bytes, key, 16, 16) }
            assertRejected { writeDemoPng(bytes, key, 16, 16) }
            assertEquals(0, bytes.size())
            assertRejected { context.responsesImageSources(listOf(Attachment("ref", "ref", imageKey = key)), provider) }
        }
    }

    @Test fun invalidSecondPngRollsBackTheWholeBatchAndLeavesExistingFilesIntact() = runBlocking(Dispatchers.IO) {
        val good = png(32, 16, Color.MAGENTA)
        val existing = store.saveBatch(listOf(good)).single()
        val root = File(context.filesDir, "generated-images")
        val before = root.list().orEmpty().toSet()
        val wrongCrc = good.copyOf().apply { this[29] = (this[29].toInt() xor 0x80).toByte() }
        val truncated = good.copyOf(good.size - 7)
        // Keep the IHDR CRC valid while falsely doubling its width. Its decoded rows cannot match.
        val wrongSize = good.copyOf().apply {
            val header = ByteBuffer.wrap(this)
            header.putInt(16, 64)
            val crc = CRC32().apply { update(header.array(), 12, 17) }
            header.putInt(29, crc.value.toInt())
        }
        for (invalid in listOf(wrongCrc, truncated, wrongSize)) {
            assertRejected { store.saveBatch(listOf(good, invalid)) }
            assertEquals(before, root.list().orEmpty().toSet())
            assertArrayEquals(good, original(existing))
        }
    }

    @Test fun discardingOneImageKeepsTheOtherImageInItsBatch() = runBlocking(Dispatchers.IO) {
        val first = png(8, 8, Color.WHITE)
        val second = png(16, 8, Color.BLACK)
        val keys = store.saveBatch(listOf(first, second))
        store.discard(listOf(keys[0]))
        assertRejected { original(keys[0]) }
        assertArrayEquals(second, original(keys[1]))
    }

    // Refusing the operation and skipping the unsafe entry are both safe for cleanup.
    private suspend fun cleanupMayReject(block: suspend () -> Unit) {
        try {
            block()
        } catch (_: IOException) {
            return
        } catch (_: IllegalArgumentException) {
            return
        }
    }

    private suspend fun verifyStoreLink(select: (root: File, batch: File, image: File) -> File) {
        val image = png(16, 12, Color.CYAN)
        val key = store.saveBatch(listOf(image)).single()
        val root = File(context.filesDir, "generated-images")
        val batch = requireNotNull(root.listFiles()).single()
        val leaf = requireNotNull(batch.listFiles()).single()
        val link = select(root, batch, leaf).toPath()
        val outside = File(directory, "outside-store").toPath()
        val outsideImage = outside.resolve(link.relativize(leaf.toPath()))
        Files.move(link, outside)
        Files.createSymbolicLink(link, outside)
        // A cleanup that follows this link would mistake its target for a stale batch.
        assertTrue(batch.setLastModified(System.currentTimeMillis() - TimeUnit.DAYS.toMillis(2)))

        assertRejected { original(key) }
        assertRejected { store.decodePreview(key, 16).recycle() }
        cleanupMayReject { store.discard(listOf(key)) }
        assertTrue(Files.isSymbolicLink(link))
        assertArrayEquals(image, Files.readAllBytes(outsideImage))
        cleanupMayReject { store.pruneUnreferenced(emptySet()) }
        assertTrue(Files.isSymbolicLink(link))
        assertArrayEquals(image, Files.readAllBytes(outsideImage))
    }

    @Test fun linkedImageFileIsNeverReadOrDeleted() = runBlocking(Dispatchers.IO) {
        verifyStoreLink { _, _, image -> image }
    }

    @Test fun linkedBatchDirectoryIsNeverFollowedOrDeleted() = runBlocking(Dispatchers.IO) {
        verifyStoreLink { _, batch, _ -> batch }
    }

    @Test fun linkedStoreDirectoryIsNeverFollowedOrDeleted() = runBlocking(Dispatchers.IO) {
        verifyStoreLink { root, _, _ -> root }
    }

    @Test fun pruningSkipsStalePendingDirectoryLinks() = runBlocking(Dispatchers.IO) {
        val root = File(context.filesDir, "generated-images").apply { check(mkdir()) }
        val outside = File(directory, "outside-pending").apply { check(mkdir()) }
        val sentinel = File(outside, "keep.png")
        val expected = png(8, 4, Color.GREEN)
        sentinel.writeBytes(expected)
        val link = File(root, ".pending-${UUID.randomUUID()}").toPath()
        Files.createSymbolicLink(link, outside.toPath())
        assertTrue(outside.setLastModified(System.currentTimeMillis() - TimeUnit.DAYS.toMillis(2)))
        cleanupMayReject { store.pruneUnreferenced(emptySet()) }
        assertTrue(Files.isSymbolicLink(link))
        assertArrayEquals(expected, sentinel.readBytes())
    }

    @Test fun pruningAStalePendingBatchPreservesUnknownFilesAndDirectories() = runBlocking(Dispatchers.IO) {
        val pending = File(context.filesDir, "generated-images/.pending-${UUID.randomUUID()}").apply { check(mkdirs()) }
        val staged = File(pending, "0-8x4.png").apply { writeBytes(png(8, 4, Color.GRAY)) }
        val keep = File(pending, "other.txt").apply { writeText("keep file") }
        val unknownDirectory = File(pending, "1-8x4.png").apply { check(mkdir()) }
        val nested = File(unknownDirectory, "keep.txt").apply { writeText("keep directory") }
        assertTrue(pending.setLastModified(System.currentTimeMillis() - TimeUnit.DAYS.toMillis(2)))
        store.pruneUnreferenced(emptySet())
        assertFalse(staged.exists())
        assertEquals("keep file", keep.readText())
        assertEquals("keep directory", nested.readText())
    }

    @Test fun pruningKeepsReferencedOldImagesAndUnreferencedFreshBatches() = runBlocking(Dispatchers.IO) {
        val image = png(12, 16, Color.YELLOW)
        val old = store.saveBatch(listOf(image, image))
        val root = File(context.filesDir, "generated-images")
        val oldBatch = requireNotNull(root.listFiles()).single()
        val fresh = store.saveBatch(listOf(image)).single()
        assertTrue(oldBatch.setLastModified(System.currentTimeMillis() - TimeUnit.DAYS.toMillis(2)))
        store.pruneUnreferenced(setOf(old[0]))
        assertArrayEquals(image, original(old[0]))
        assertRejected { original(old[1]) }
        assertArrayEquals(image, original(fresh))
    }
}
