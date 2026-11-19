package io.hydrion.hub.ui.messages

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import androidx.exifinterface.media.ExifInterface
import android.net.Uri
import android.os.Build
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import java.io.File
import java.io.IOException
import java.io.OutputStream
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

private val previews = object : LruCache<String, Bitmap>(12 * 1024 * 1024) {
    override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
}
private val renderSlots = Semaphore(2)

private sealed interface Preview {
    data object Loading : Preview
    data class Ready(val bitmap: Bitmap) : Preview
    data object Failed : Preview
}

private fun scaledSize(width: Int, height: Int, maxEdge: Int): Pair<Int, Int> {
    val ratio = min(1.0, maxEdge.toDouble() / max(width, height).coerceAtLeast(1))
    return max(1, (width * ratio).roundToInt()) to max(1, (height * ratio).roundToInt())
}

@Composable
internal fun MockPicture(
    key: String,
    width: Int,
    height: Int,
    description: String,
    modifier: Modifier = Modifier,
    fit: Boolean = false,
) {
    val (w, h) = scaledSize(width, height, if (fit) 1440 else 480)
    val cacheId = "mock/$w/$h/$key"
    BitmapPreview(cacheId, description, modifier, fit) {
        renderLandscape(key, w, h)
    }
}

@Composable
internal fun AttachmentPicture(uri: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current.applicationContext
    BitmapPreview("attachment/$uri", "图片附件", modifier, false) {
        decodeAttachment(context, Uri.parse(uri), 640)
    }
}

@Composable
private fun BitmapPreview(
    cacheId: String,
    description: String,
    modifier: Modifier,
    fit: Boolean,
    load: () -> Bitmap,
) {
    val preview by key(cacheId) {
        produceState<Preview>(initialValue = previews.get(cacheId)?.let { Preview.Ready(it) } ?: Preview.Loading) {
            if (value is Preview.Ready) return@produceState
            value = try {
                val bitmap = withContext(Dispatchers.IO) {
                    renderSlots.withPermit {
                        previews.get(cacheId) ?: load().also { previews.put(cacheId, it) }
                    }
                }
                Preview.Ready(bitmap)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                Preview.Failed
            }
        }
    }
    Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
        when (val result = preview) {
            is Preview.Ready -> Image(
                bitmap = result.bitmap.asImageBitmap(),
                contentDescription = description,
                modifier = Modifier.fillMaxSize(),
                contentScale = if (fit) ContentScale.Fit else ContentScale.Crop,
            )
            Preview.Loading -> CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            Preview.Failed -> Text("无法读取图片", style = MaterialTheme.typography.labelSmall)
        }
    }
}

private fun decodeAttachment(context: Context, uri: Uri, maxEdge: Int): Bitmap {
    require(uri.scheme == "content") { "图片地址无效" }
    val resolver = context.contentResolver
    if (Build.VERSION.SDK_INT >= 28) {
        return ImageDecoder.decodeBitmap(ImageDecoder.createSource(resolver, uri)) { decoder, info, _ ->
            val (width, height) = scaledSize(info.size.width, info.size.height, maxEdge)
            decoder.setTargetSize(width, height)
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
    }
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
    require(bounds.outWidth > 0 && bounds.outHeight > 0) { "无法读取图片" }
    val options = BitmapFactory.Options().apply {
        while (max(bounds.outWidth, bounds.outHeight) / inSampleSize.coerceAtLeast(1) > maxEdge * 2) {
            inSampleSize = inSampleSize.coerceAtLeast(1) * 2
        }
    }
    val bitmap = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
        ?: throw IOException("无法读取图片")
    val orientation = try {
        resolver.openInputStream(uri)?.use {
            ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        }
    } catch (_: IOException) {
        null
    }
    val matrix = Matrix()
    when (orientation) {
        ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.setScale(-1f, 1f)
        ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.setScale(1f, -1f)
        ExifInterface.ORIENTATION_ROTATE_180 -> matrix.setRotate(180f)
        ExifInterface.ORIENTATION_ROTATE_90 -> matrix.setRotate(90f)
        ExifInterface.ORIENTATION_ROTATE_270 -> matrix.setRotate(-90f)
        ExifInterface.ORIENTATION_TRANSPOSE, ExifInterface.ORIENTATION_TRANSVERSE -> {
            matrix.setRotate(if (orientation == ExifInterface.ORIENTATION_TRANSPOSE) 90f else -90f)
            matrix.postScale(-1f, 1f)
        }
        else -> return bitmap
    }
    return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        .also { if (it !== bitmap) bitmap.recycle() }
}

internal suspend fun saveMockPicture(context: Context, uri: Uri, key: String, width: Int, height: Int) {
    withContext(Dispatchers.IO) {
        requireExportSize(width, height)
        val resolver = context.contentResolver
        val stream = resolver.openOutputStream(uri, "wt") ?: throw IOException("无法打开保存位置")
        stream.use { writePng(it, key, width, height) }
    }
}

internal suspend fun copyMockPicture(context: Context, key: String, width: Int, height: Int) {
    val uri = withContext(Dispatchers.IO) {
        requireExportSize(width, height)
        val directory = File(context.cacheDir, "copies")
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("无法打开图片缓存")
        val expiry = System.currentTimeMillis() - 24 * 60 * 60 * 1000L
        directory.listFiles()?.filter { it.isFile && it.lastModified() < expiry }?.forEach { it.delete() }
        val file = File.createTempFile("HydrionHub-", ".png", directory)
        try {
            file.outputStream().use { writePng(it, key, width, height) }
            FileProvider.getUriForFile(context, context.packageName + ".images", file)
        } catch (e: Exception) {
            file.delete()
            throw e
        }
    }
    withContext(Dispatchers.Main.immediate) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newUri(context.contentResolver, "HydrionHub 图片", uri))
    }
}

private fun requireExportSize(width: Int, height: Int) {
    require(width in 1..4096 && height in 1..4096 && width.toLong() * height <= 8_388_608L) {
        "超出本地图片导出尺寸上限"
    }
}

internal suspend fun writePng(stream: OutputStream, key: String, width: Int, height: Int) {
    requireExportSize(width, height)
    renderSlots.withPermit {
        val bitmap = renderLandscape(key, width, height)
        try {
            if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)) throw IOException("PNG 写入失败")
        } finally {
            bitmap.recycle()
        }
    }
}

// Each key describes one scene independent of raster size, so exported PNGs match their thumbnails.
private data class Inks(
    val sky: Int, val horizon: Int, val sun: Int,
    val far: Int, val middle: Int, val near: Int, val shore: Int, val water: Int,
)
private fun inks(vararg hex: String): Inks {
    val c = hex.map { Color.parseColor("#" + it) }
    return Inks(c[0], c[1], c[2], c[3], c[4], c[5], c[6], c[7])
}
private val palettes = listOf(
    inks("A6C2C1", "F8CE9E", "FFE9BC", "9FABA4", "758D88", "395A60", "223B46", "7BA6A1"),
    inks("B7A5BB", "E9B6A7", "FFF0C4", "ACA0AF", "817E96", "535672", "313749", "9194A7"),
    inks("B9D1D8", "F5DEC0", "FFF6D7", "B8C2BA", "8DABAA", "516F76", "284951", "85AFB0"),
    inks("DEB890", "F3D2A1", "FFF2CD", "C9A991", "AC836E", "785F58", "493F43", "B6A99A"),
)

private fun renderLandscape(key: String, width: Int, height: Int): Bitmap {
    val image = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val random = Random(key.hashCode())
    val ink = palettes[random.nextInt(palettes.size)]
    val c = Canvas(image)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    val w = width.toFloat()
    val h = height.toFloat()
    val small = min(w, h)

    fun gradient(top: Float, bottom: Float, a: Int, b: Int) {
        paint.shader = LinearGradient(0f, h * top, 0f, h * bottom, a, b, Shader.TileMode.CLAMP)
        c.drawRect(0f, top * h, w, bottom * h, paint)
        paint.shader = null
    }
    fun polygon(color: Int, vararg xy: Float) {
        val path = Path().apply {
            moveTo(xy[0] * w, xy[1] * h)
            for (i in 2 until xy.size step 2) lineTo(xy[i] * w, xy[i + 1] * h)
            close()
        }
        paint.color = color
        c.drawPath(path, paint)
    }
    fun ridge(color: Int, base: Float, heightScale: Float, roughness: Float) {
        val phase = random.nextFloat() * 6f
        val phase2 = random.nextFloat() * 6f
        val path = Path().apply {
            moveTo(-w * .05f, h)
            for (step in -2..82) {
                val x = step / 80f
                val rolling = sin(x * 7f + phase) * .53f + sin(x * 16f + phase2) * .28f +
                    cos(x * 31f + phase) * .13f + (random.nextFloat() - .5f) * roughness
                lineTo(x * w, (base - heightScale * rolling) * h)
            }
            lineTo(1.05f * w, h)
            close()
        }
        paint.color = color
        c.drawPath(path, paint)
    }
    gradient(0f, 1f, ink.sky, ink.horizon)
    val sunX = (.23f + random.nextFloat() * .55f) * w
    val sunY = (.21f + random.nextFloat() * .1f) * h
    val sunR = (.063f + random.nextFloat() * .046f) * small
    paint.color = ink.sun
    c.drawCircle(sunX, sunY, sunR, paint)
    val scene = random.nextInt(3)
    ridge(ink.far, .53f, .12f, .1f)
    if (scene == 0) {
        // A triangular sunlit face and the broken shoulder of the shaded ridge.
        val summit = .25f + random.nextFloat() * .43f
        polygon(ink.middle, -.06f,.73f, .09f,.59f, summit-.13f,.47f, summit-.07f,.46f,
            summit,.28f, summit+.055f,.36f, summit+.09f,.39f, summit+.2f,.57f, 1.02f,.68f,
            1.02f,1f, -.06f,1f)
        polygon(ink.far, summit,.28f, summit+.055f,.36f, summit+.09f,.39f,
            summit+.2f,.57f, summit+.085f,.50f, summit+.064f,.435f, summit+.025f,.409f)
        polygon(ink.horizon, summit,.28f, summit+.04f,.338f, summit+.022f,.332f,
            summit+.029f,.355f, summit+.009f,.34f, summit-.012f,.35f)
        ridge(ink.near, .65f, .105f, .08f)
    } else if (scene == 1) {
        // Rounded coastal uplands, ending at a flat bluff with a steep sea-facing edge.
        ridge(ink.middle, .57f, .12f, .07f)
        polygon(ink.near, -.03f,.57f, .12f,.54f, .22f,.53f, .27f,.55f, .36f,.55f,
            .40f,.58f, .50f,.59f, .49f,.64f, .55f,.68f, .61f,.69f, .62f,.73f,
            -.03f,.73f)
    } else {
        ridge(ink.middle, .55f, .14f, .03f)
        ridge(ink.near, .66f, .12f, .05f)
    }
    val shoreY = .68f + random.nextFloat() * .04f
    gradient(shoreY, 1f, ink.water, ink.middle)
    // Wind and a broken reflection on the water; plotted in scene coordinates at every resolution.
    paint.color = ink.sun
    paint.strokeCap = Paint.Cap.ROUND
    repeat(170) {
        val d = random.nextFloat()
        val y = (shoreY + (.99f - shoreY) * d) * h
        val x = sunX + (random.nextFloat() - .5f) * (.07f + .42f * d) * w
        val span = (.002f + random.nextFloat() * .028f) * w
        paint.alpha = (13 + random.nextInt(48))
        paint.strokeWidth = max(.32f, (.00065f + d * .0014f) * small)
        c.drawLine(x, y, x + span, y, paint)
    }
    paint.alpha = 255
    if (random.nextBoolean()) {
        polygon(ink.shore, -.02f,.71f, .10f,.72f, .21f,.76f, .26f,.78f, .36f,.79f,
            .41f,.81f, .49f,.82f, .29f,.835f, .1f,.865f, -.02f,.9f)
        polygon(ink.near, 1.01f,.89f, .92f,.9f, .8f,.935f, .57f,.97f, .5f,1.01f, 1.01f,1.01f)
    } else {
        polygon(ink.near, 1.01f,.75f, .94f,.74f, .82f,.77f, .78f,.79f, .62f,.804f,
            .55f,.818f, .70f,.823f, .88f,.84f, 1.01f,.84f)
        polygon(ink.shore, -.01f,.86f, .08f,.857f, .16f,.878f, .20f,.91f,
            .33f,.934f, .37f,.941f, .45f,.973f, .56f,1.01f, -.01f,1.01f)
    }
    // Fine paper flecks give the small print illustrations a matte texture.
    val points = FloatArray(7000)
    for (i in points.indices step 2) {
        points[i] = random.nextFloat() * w
        points[i + 1] = random.nextFloat() * h
    }
    paint.color = Color.argb(30, 255, 247, 219)
    paint.strokeWidth = max(.35f, small * .0011f)
    c.drawPoints(points, paint)
    return image
}
