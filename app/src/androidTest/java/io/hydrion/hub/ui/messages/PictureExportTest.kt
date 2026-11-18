package io.hydrion.hub.ui.messages

import android.graphics.BitmapFactory
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PictureExportTest {
    @Test fun pngKeepsOriginalDimensionsAndSceneOnRepeatedSaves() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File.createTempFile("scene-", ".png", context.cacheDir)
        try {
            val uri = Uri.fromFile(file)
            saveMockPicture(context, uri, "persistent-image", 240, 160)
            val first = file.readBytes()
            val decoded = requireNotNull(BitmapFactory.decodeFile(file.path))
            try {
                assertEquals(240, decoded.width)
                assertEquals(160, decoded.height)
                assertTrue(decoded.getPixel(10, 10) != decoded.getPixel(230, 150))
            } finally {
                decoded.recycle()
            }
            saveMockPicture(context, uri, "persistent-image", 240, 160)
            assertArrayEquals(first, file.readBytes())
        } finally {
            file.delete()
        }
    }
}
