package io.hydrion.hub.ui.messages

import androidx.compose.ui.text.AnnotatedString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

class CodeBlockCopyTest {
    @Test fun writesPlainCodeBeforeShowingConfirmationOnOlderAndroid() {
        val body = "\t**literal**  \n\n```\nend  "
        val block = markdownBlocks("````markdown linenums=1\n$body\n````").single() as MarkdownBlock.Code

        for (sdk in listOf(32, 33, 35)) {
            val events = mutableListOf<String>()
            val written = mutableListOf<AnnotatedString>()
            block.copyToClipboard(sdk, setText = {
                written += it
                events += "write"
            }, confirm = { events += "confirm" })

            assertEquals("SDK $sdk", listOf(AnnotatedString(body)), written)
            val expected = if (sdk == 32) listOf("write", "confirm") else listOf("write")
            assertEquals("SDK $sdk", expected, events)
        }
    }

    @Test fun failedWriteDoesNotConfirmSuccess() {
        val block = markdownBlocks("```\ntext\n```").single() as MarkdownBlock.Code
        val failure = IllegalStateException("clipboard unavailable")
        var confirmations = 0
        val thrown = assertThrows(IllegalStateException::class.java) {
            block.copyToClipboard(32, setText = { throw failure }, confirm = { confirmations++ })
        }
        assertSame(failure, thrown)
        assertEquals(0, confirmations)
    }
}
