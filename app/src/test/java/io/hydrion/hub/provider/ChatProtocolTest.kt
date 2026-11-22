package io.hydrion.hub.provider

import io.hydrion.hub.R
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

    @Test fun savingRemoteHttpProvidersUsesTheSendTimeEndpointRules() {
        for (protocol in listOf(ChatProtocol.ChatCompletionsHttp, ChatProtocol.ResponsesHttp)) {
            val remote = provider.copy(protocol = protocol.label)
            assertNull(validateProvider(remote.copy(baseUrl = "https://fixture.invalid/tenant/v1"), emptyMap()))
            for (base in listOf("http://fixture.invalid/v1", "https://fixture.invalid/v1?extra=1",
                "https://fixture.invalid:70000/v1", "https://fixture.invalid/v1/../tenant")) {
                assertEquals(base, R.string.model_invalid_service, validateProvider(remote.copy(baseUrl = base), emptyMap()))
            }
        }
    }

    @Test fun savingUnconfiguredRemoteAndLegacyProvidersKeepsTheirExistingRules() {
        for (protocol in ChatProtocol.entries) {
            val empty = provider.copy(protocol = protocol.label, baseUrl = "")
            assertNull(validateProvider(empty, emptyMap()))
        }
        val local = provider.copy(protocol = "OpenAI Compatible", baseUrl = "http://fixture.invalid/v1")
        assertNull(validateProvider(local, emptyMap()))
        val image = local.copy(mode = Mode.Image, protocol = "OpenAI Images", requestBody = "{{missing}}")
        assertEquals(R.string.model_body_invalid, validateProvider(image, emptyMap()))
        assertEquals(R.string.model_valid_url, validateProvider(image.copy(baseUrl = "wss://fixture.invalid"), emptyMap()))
    }

    @Test fun webSocketAcceptsOnlyOfficialOpenAiWithNoCustomPathOrAuthority() {
        val ws = provider.copy(protocol = ChatProtocol.ResponsesWebSocket.label)
        val target = "wss://api.openai.com/v1/responses"
        for (base in listOf(target, "https://api.openai.com", "https://API.OPENAI.COM:443/v1/",
            "https://api.openai.com/v1/responses")) {
            val valid = ws.copy(baseUrl = base)
            assertEquals(target, valid.chatEndpoint())
            assertNull(base, validateProvider(valid, emptyMap()))
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
            if (base.isNotEmpty()) assertEquals(base, R.string.model_invalid_service, validateProvider(invalid, emptyMap()))
            assertTrue(runCatching { invalid.chatEndpoint() }.isFailure)
        }
        assertNotNull(deepSeekProvider.copy(protocol = ws.protocol, baseUrl = target).chatProtocolIssue())
    }
}
