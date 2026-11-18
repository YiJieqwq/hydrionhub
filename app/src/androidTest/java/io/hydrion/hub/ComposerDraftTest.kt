package io.hydrion.hub

import android.graphics.Bitmap
import android.view.KeyEvent
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.hydrion.hub.model.Attachment
import io.hydrion.hub.model.HubViewModel
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ComposerDraftTest {
    @get:Rule val screen = createAndroidComposeRule<MainActivity>()

    private val input = hasContentDescription("消息输入框") and hasSetTextAction()

    private fun waitFor(matcher: SemanticsMatcher, count: Int = 1) {
        screen.waitUntil(timeoutMillis = 15_000) {
            screen.onAllNodes(matcher).fetchSemanticsNodes().size == count
        }
    }

    @Test
    fun hidingKeyboardNarrowsComposerButKeepsDraftAndControls() {
        screen.onAllNodes(input).assertCountEquals(1)
        screen.onNodeWithContentDescription("打开工具箱").assertDoesNotExist()
        screen.onNode(input).performClick()
        waitFor(input and isFocused())
        screen.onNode(input).performTextInput("继续编辑")
        screen.onNodeWithContentDescription("打开工具箱").assertIsDisplayed()
        val editingWidth = screen.onNode(input).fetchSemanticsNode().size.width

        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        waitFor(input and isFocused(), count = 0)
        screen.onAllNodes(input).assertCountEquals(1)
        screen.onNode(input).assertTextContains("继续编辑")
        screen.onNodeWithContentDescription("打开工具箱").assertIsDisplayed()
        screen.onNodeWithContentDescription("配置模型").assertIsDisplayed()
        assertTrue(screen.onNode(input).fetchSemanticsNode().size.width < editingWidth)

        screen.onNode(input).performClick().performTextInput("中")
        screen.onNode(input).assertTextContains("继续编辑中")
        screen.onNodeWithContentDescription("发送消息").assertIsEnabled()
    }

    @Test
    fun draftUsesDecodedImageAndGeneratedReferenceThumbnails() {
        val context = screen.activity.applicationContext
        val dir = File(context.cacheDir, "copies").apply { mkdirs() }
        val file = File.createTempFile("composer-test-", ".png", dir)
        try {
            val bitmap = Bitmap.createBitmap(4, 3, Bitmap.Config.ARGB_8888)
            try {
                bitmap.eraseColor(android.graphics.Color.MAGENTA)
                file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            } finally {
                bitmap.recycle()
            }
            val uri = FileProvider.getUriForFile(context, context.packageName + ".images", file)
            screen.runOnIdle {
                val vm = ViewModelProvider(screen.activity)[HubViewModel::class.java]
                vm.addAttachment(Attachment("draft-photo", "小图.png", uri.toString(), mimeType = "image/png"))
                vm.referenceImage("composer-fixture", 512, 256)
                vm.addAttachment(Attachment("draft-doc", "资料.txt", mimeType = "text/plain"))
            }

            // BitmapPreview exposes these image semantics only after a successful decode/render.
            waitFor(hasContentDescription("图片附件"))
            waitFor(hasContentDescription("参考图缩略图"))
            screen.onNodeWithContentDescription("打开工具箱").assertIsDisplayed()
            screen.onNodeWithContentDescription("发送消息").assertIsEnabled()
            screen.onNodeWithContentDescription("移除 小图.png").performSemanticsAction(SemanticsActions.OnClick)
            screen.onNodeWithContentDescription("图片附件").assertDoesNotExist()
            screen.onNodeWithContentDescription("移除 参考图").performSemanticsAction(SemanticsActions.OnClick)
            screen.onNodeWithText("资料.txt").assertIsDisplayed()
            screen.onNodeWithContentDescription("移除 资料.txt").performSemanticsAction(SemanticsActions.OnClick)
            screen.onNodeWithContentDescription("打开工具箱").assertDoesNotExist()
            screen.onNodeWithContentDescription("发送消息").assertIsNotEnabled()
        } finally {
            file.delete()
        }
    }
}
