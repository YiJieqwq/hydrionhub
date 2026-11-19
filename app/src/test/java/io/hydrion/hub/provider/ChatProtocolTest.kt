package io.hydrion.hub.provider

import io.hydrion.hub.model.Mode
import org.junit.Assert.*
import org.junit.Test

class ChatProtocolTest {
    private val provider = Provider("custom", Mode.Chat, "Test", ChatProtocol.ChatCompletionsHttp.label,
        "https://api.openai.com/v1", emptyList())

    @Test fun legacyAndDeliberateChoicesSelectTheRightService() {
        assertEquals(ChatProtocol.ResponsesHttp, deepSeekProvider.chatProtocol())
        val deepSeek = deepSeekProvider.copy(protocol = provider.protocol, baseUrl = "")
        assertEquals(ChatProtocol.ChatCompletionsHttp, deepSeek.chatProtocol())
        assertEquals("https://api.deepseek.com/chat/completions", deepSeek.deepSeekEndpoint())
        assertEquals("https://api.openai.com/v1/chat/completions", provider.chatEndpoint())
        assertEquals("https://proxy.test/tenant/v1/chat/completions",
            provider.copy(baseUrl = "https://proxy.test/tenant/v1/responses").chatEndpoint())
        assertEquals("https://api.openai.com/v1/responses",
            provider.copy(protocol = ChatProtocol.ResponsesHttp.label, baseUrl = provider.chatEndpoint()).chatEndpoint())
        assertNull(provider.copy(protocol = "custom", baseUrl = "").chatProtocolIssue())
        assertNotNull(provider.copy(protocol = "custom", baseUrl = "https://proxy.test").chatProtocolIssue())
        assertNull(provider.copy(protocol = "OpenAI Compatible", baseUrl = "https://proxy.test").chatProtocol())
        assertNotNull(provider.copy(protocol = "OpenAI Compatible").chatProtocolIssue())
        assertNotNull(provider.copy(id = "demo-chat").chatProtocolIssue())
        assertNotNull(deepSeek.copy(baseUrl = provider.baseUrl).chatProtocolIssue())
    }

    @Test fun webSocketAcceptsOnlyOfficialOpenAiWithNoCustomPathOrAuthority() {
        val ws = provider.copy(protocol = ChatProtocol.ResponsesWebSocket.label)
        val target = "wss://api.openai.com/v1/responses"
        for (base in listOf(target, "https://api.openai.com", "https://API.OPENAI.COM:443/v1/",
            "https://api.openai.com/v1/responses")) {
            assertEquals(target, ws.copy(baseUrl = base).chatEndpoint())
        }
        for (base in listOf("", "ws://api.openai.com/v1/responses", "wss://api.deepseek.com/v1/responses",
            "wss://api.openai.com.proxy.test/v1/responses", "wss://api.openai.com./v1/responses",
            "wss://api.openai.com:/v1/responses", "wss://api.openai.com:444/v1/responses",
            "wss://api.openai.com@proxy.test/v1/responses", "wss://test@api.openai.com/v1/responses",
            "wss://api.openai.com/v1/responses?x", "wss://api.openai.com/v1/responses#x",
            "https://api.openai.com/tenant/v1", "wss://api.openai.com/v1/%72esponses",
            "https://api.openai.com/v1/../v1/responses")) {
            val invalid = ws.copy(baseUrl = base)
            assertNotNull(invalid.chatProtocolIssue())
            assertTrue(runCatching { invalid.chatEndpoint() }.isFailure)
        }
        assertNotNull(deepSeekProvider.copy(protocol = ws.protocol, baseUrl = target).chatProtocolIssue())
    }
}
