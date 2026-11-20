package io.hydrion.hub

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.hydrion.hub.model.Attachment
import io.hydrion.hub.model.HubViewModel
import io.hydrion.hub.ui.HydrionApp
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DraftImagePreviewTest {
    @get:Rule val screen = createAndroidComposeRule<MainActivity>()
    private val editor get() = screen.onNode(hasSetTextAction() and hasContentDescription("消息输入框"))
    private val tray get() = screen.onNodeWithTag("draft-attachments")

    private fun markedPhoto(file: File, width: Int, height: Int, withDimensions: Boolean): Attachment {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bitmap)
            canvas.drawColor(Color.GRAY)
            val paint = Paint()
            val w = width.toFloat()
            val h = height.toFloat()
            paint.color = Color.RED
            canvas.drawRect(0f, 0f, w * .14f, h, paint)
            paint.color = Color.GREEN
            canvas.drawRect(w * .86f, 0f, w, h, paint)
            paint.color = Color.BLUE
            canvas.drawRect(0f, 0f, w, h * .14f, paint)
            paint.color = Color.YELLOW
            canvas.drawRect(0f, h * .86f, w, h, paint)
            file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally {
            bitmap.recycle()
        }
        val context = screen.activity.applicationContext
        val uri = FileProvider.getUriForFile(context, context.packageName + ".images", file)
        return Attachment(file.name, file.name, uri = uri.toString(), mimeType = "image/png",
            width = width.takeIf { withDimensions }, height = height.takeIf { withDimensions })
    }

    private fun checkFullPhoto(photo: Attachment, ratio: Float, index: Int, checkEdges: Boolean = true): Int {
        tray.performScrollToIndex(index)
        val tag = hasTestTag("draftImage:${photo.id}")
        val readyImage = hasContentDescription("图片附件") and hasAnyAncestor(tag)
        screen.waitUntil(15_000) { screen.onAllNodes(readyImage, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        val preview = screen.onNode(tag, useUnmergedTree = true).assertIsDisplayed()
        val bounds = preview.fetchSemanticsNode().size
        val density = screen.activity.resources.displayMetrics.density
        assertTrue("Both fixtures stay above the 32dp remove-button minimum", bounds.width > 32f * density)
        assertEquals("uncropped dimensions for ${photo.name}", bounds.height * ratio, bounds.width.toFloat(), 2f)
        assertTrue("120dp height cap", bounds.height <= 120f * density + 1f)
        if (!checkEdges) return bounds.height
        val bitmap = preview.captureToImage().asAndroidBitmap()
        assertEquals("Completely visible image width", bounds.width, bitmap.width)
        assertEquals("Completely visible image height", bounds.height, bitmap.height)
        // Geometry alone would miss a zoom/crop inside a correctly sized frame.
        fun pixel(x: Float, y: Float) = bitmap.getPixel((x * bitmap.width).toInt(), (y * bitmap.height).toInt())
        assertEquals("left source edge", Color.RED, pixel(.07f, .56f))
        assertEquals("right source edge", Color.GREEN, pixel(.93f, .56f))
        assertEquals("top source edge", Color.BLUE, pixel(.29f, .07f))
        assertEquals("bottom source edge", Color.YELLOW, pixel(.29f, .93f))
        return bitmap.height
    }

    @Test fun multipleSourceRatiosSurviveScrollingAndSingleRemovalInANarrowComposer() {
        val vm = screen.runOnIdle { ViewModelProvider(screen.activity)[HubViewModel::class.java] }
        val before = vm.state.value
        val short = mutableStateOf(false)
        val context = screen.activity.applicationContext
        val copies = File(context.cacheDir, "copies").apply { mkdirs() }
        val wideFile = File.createTempFile("wide-", ".png", copies)
        val tallFile = File.createTempFile("tall-", ".png", copies)
        val caption = "待发送"
        try {
            // Existing URI attachments can have no width or height. The renderer must use the actual image.
            val wide = markedPhoto(wideFile, 160, 100, withDimensions = false)
            val tall = markedPhoto(tallFile, 80, 100, withDimensions = true)
            screen.runOnUiThread {
                screen.activity.setContent {
                    Box(Modifier.fillMaxSize()) {
                        Box(Modifier.widthIn(max = 320.dp).heightIn(max = if (short.value) 200.dp else 600.dp)
                            .testTag("draft-viewport")) { HydrionApp() }
                    }
                }
            }
            screen.runOnIdle {
                vm.setDraft(caption)
                vm.addAttachment(wide)
                vm.addAttachment(tall)
            }
            val firstIndex = before.draft.attachments.size
            checkFullPhoto(wide, 1.6f, firstIndex)
            val normalHeight = checkFullPhoto(tall, .8f, firstIndex + 1)

            tray.performScrollToIndex(firstIndex)
            screen.onNodeWithContentDescription("移除 ${wide.name}").performClick()
            screen.runOnIdle {
                assertEquals(before.draft.attachments + tall, vm.state.value.draft.attachments)
                assertEquals(before.current.messages, vm.state.value.current.messages)
                assertEquals(caption, vm.state.value.draft.text)
                short.value = true
            }
            // Without tapping the field or summoning the OS keyboard, constrain available height too.
            val smallHeight = checkFullPhoto(tall, .8f, firstIndex, checkEdges = false)
            assertTrue("Short viewport reduces the preview", smallHeight < normalHeight)
            val field = editor.assertIsDisplayed().assertTextEquals(caption).fetchSemanticsNode().boundsInRoot
            val host = screen.onNodeWithTag("draft-viewport").fetchSemanticsNode().boundsInRoot
            val image = screen.onNodeWithTag("draftImage:${tall.id}", true).fetchSemanticsNode().boundsInRoot
            assertTrue("Editor remains inside the viewport and below the preview",
                field.top >= image.bottom && field.bottom <= host.bottom && field.left >= host.left && field.right <= host.right)
            val layouts = mutableListOf<TextLayoutResult>()
            editor.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            val text = layouts.single()
            assertEquals("Actual input size", 16.sp, text.layoutInput.style.fontSize)
            assertTrue("There is room for a complete line of real input",
                field.height >= text.getLineBottom(0) - text.getLineTop(0) && !text.didOverflowWidth)
            screen.onNodeWithContentDescription("发送消息").assertIsDisplayed().assertIsEnabled()
        } finally {
            screen.runOnIdle {
                vm.removeAttachment(wideFile.name)
                vm.removeAttachment(tallFile.name)
                vm.setDraft(before.draft.text)
            }
            wideFile.delete()
            tallFile.delete()
        }
    }
}
