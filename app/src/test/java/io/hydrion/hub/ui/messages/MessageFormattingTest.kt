package io.hydrion.hub.ui.messages

import io.hydrion.hub.model.*
import org.junit.Assert.assertEquals
import org.junit.Test

class MessageFormattingTest {
    @Test fun incompleteFencedCodeIsReadableDuringStreaming() {
        val text = "# 示例\n\n- 粗体\n- 行内代码\n\n```kotlin\nfun hello() ="
        assertEquals(listOf(
            MarkdownBlock.Heading("示例", 1),
            MarkdownBlock.Paragraph("• 粗体\n• 行内代码"),
            MarkdownBlock.Code("kotlin", "fun hello() =")
        ), markdownBlocks(text))
    }

    @Test fun fencedCodeUsesTheFirstInfoWordAsItsLanguage() {
        val body = "  int main() {  \n\treturn 0;\n}  "
        val text = "```  C++\tlinenums=1 file=main.cpp\n$body\n```\n``` \t \n  plain text\n```"
        assertEquals(listOf(
            MarkdownBlock.Code("C++", body),
            MarkdownBlock.Code("", "  plain text")
        ), markdownBlocks(text))
    }

    @Test fun streamingCodePreservesTheLanguageAndUnfinishedBody() {
        val body = "\tval a = 1  \n  val b = "
        val text = "```\tKotlIN title=Sample.kt\n$body"
        assertEquals(listOf(MarkdownBlock.Code("KotlIN", body)), markdownBlocks(text))
    }

    @Test fun fourBackticksCanEncloseAMarkdownCodeExample() {
        val example = "```kotlin\nval value = 1\n```"
        val text = "````markdown\n$example\n`````\nAfter"
        assertEquals(listOf(
            MarkdownBlock.Code("markdown", example), MarkdownBlock.Paragraph("After")
        ), markdownBlocks(text))
    }

    @Test fun onlyAMatchingStandaloneFenceClosesTheCodeBlock() {
        val body = listOf("~~~~", "```", "```` trailing text", "    ````", "last  ").joinToString("\n")
        val text = "````text\n$body\n   ```` \t\nAfter"
        assertEquals(listOf(
            MarkdownBlock.Code("text", body), MarkdownBlock.Paragraph("After")
        ), markdownBlocks(text))
    }

    @Test fun indentedTildeFencePreservesTheBodyAsStreamingCompletes() {
        val partial = "  ~~~Js title=example\n\tconst value = 1;  \n  ```\n ~"
        val block = MarkdownBlock.Code("Js", "  const value = 1;  \n```\n~")
        assertEquals(listOf(block), markdownBlocks(partial))
        assertEquals(listOf(block, MarkdownBlock.Paragraph("After")),
            markdownBlocks("$partial\n ~~~~\t\nAfter"))
    }

    @Test fun deeplyIndentedBacktickFenceKeepsLiteralIndentationAndEndsBeforeTheNextParagraph() {
        val body = "        val answer = 42"
        val text = "1. Example\n    ```kotlin\n$body\n    ```\nAfter"
        assertEquals(listOf(
            MarkdownBlock.Paragraph("1. Example"), MarkdownBlock.Code("kotlin", body),
            MarkdownBlock.Paragraph("After")
        ), markdownBlocks(text))
    }

    @Test fun quotesAndTextAfterCodeRemainSeparate() {
        val text = "> 甲\n> 乙\n\n```\n- 字面内容\n```\n正文"
        assertEquals(listOf(
            MarkdownBlock.Quote("甲\n乙"), MarkdownBlock.Code("", "- 字面内容"), MarkdownBlock.Paragraph("正文")
        ), markdownBlocks(text))
    }

    @Test fun partialAndCustomImageCapabilitiesHaveConsistentOutputSizes() {
        assertEquals(Extent(1024, 1024), outputSize(emptyMap()))
        assertEquals(Extent(512, 768), outputSize(mapOf("size" to "512×768")))
        assertEquals(Extent(768, 1024), outputSize(mapOf("aspect" to "3:4")))
        assertEquals(Extent(1536, 768), outputSize(mapOf("size" to "1536x1024", "aspect" to "2:1")))
        assertEquals(Extent(512, 768), outputSize(mapOf("size" to "512x768", "aspect" to "0:0")))
    }

    @Test fun searchFindsVisibleContentIncludingImageAndAttachmentDetails() {
        val state = HubState(current = Conversation(messages = listOf(
            Message(id = "user", role = Role.User, attachments = listOf(Attachment("a", "Photo.PNG"))),
            Message(id = "hidden", role = Role.Assistant, prompt = "photo"),
            Message(id = "image", role = Role.Assistant, prompt = "my photo", imageKeys = listOf("first")),
            Message(id = "thought", role = Role.Assistant, thought = "photo")
        )))
        assertEquals(listOf("user", "image", "thought"), matchingMessages(state, " PHOTO "))
        assertEquals(emptyList<String>(), matchingMessages(state, " "))
    }
}
