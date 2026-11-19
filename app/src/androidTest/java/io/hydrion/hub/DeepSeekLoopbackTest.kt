package io.hydrion.hub

import android.graphics.Color
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.hydrion.hub.model.Generation
import io.hydrion.hub.model.HubViewModel
import io.hydrion.hub.model.Mode
import java.io.BufferedWriter
import java.net.InetAddress
import java.net.Socket
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.Principal
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509KeyManager
import javax.security.auth.x500.X500Principal
import kotlin.concurrent.thread
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DeepSeekLoopbackTest {
    @get:Rule val screen = createAndroidComposeRule<MainActivity>()

    @Test fun composeSendStreamsOverHttpsAndUpdatesTheModelAndContextHeader() {
        val alias = "hydrion-loopback-${UUID.randomUUID()}"
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val pair = KeyPairGenerator.getInstance("RSA", "AndroidKeyStore").apply {
            initialize(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
                .setCertificateSubject(X500Principal("CN=localhost"))
                .setDigests(KeyProperties.DIGEST_NONE, KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA384,
                    KeyProperties.DIGEST_SHA512)
                .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1, KeyProperties.SIGNATURE_PADDING_RSA_PSS)
                .build())
        }.generateKeyPair()
        val cert = store.getCertificate(alias) as X509Certificate
        val trust = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null); setCertificateEntry(alias, cert) }
        val trustManagers = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(trust) }
        val tls = SSLContext.getInstance("TLS").apply {
            init(arrayOf(FixtureKey(alias, cert, pair.private)), trustManagers.trustManagers, null)
        }
        val server = tls.serverSocketFactory.createServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val previousFactory = HttpsURLConnection.getDefaultSSLSocketFactory()
        val previousVerifier = HttpsURLConnection.getDefaultHostnameVerifier()
        val vm = screen.runOnIdle { ViewModelProvider(screen.activity)[HubViewModel::class.java] }
        val providers = vm.providers
        val catalog = providers.catalog.value
        val previousSelection = requireNotNull(catalog.selectedModel(Mode.Chat))
        val deepSeek = catalog.providers.single { it.id == "deepseek" }
        val received = mutableListOf<JSONObject>()
        val serverFailure = AtomicReference<Throwable?>()
        val allowCompletion = List(2) { CountDownLatch(1) }
        val totals = listOf(90L, 800_000L)
        val partial = "正在接收"
        val thought = "本地 HTTPS 服务已开始回复。"
        val full = "${partial}。"
        var chat = ""
        val worker = thread(name = "responses-loopback", isDaemon = true) {
            try {
                totals.forEachIndexed { index, tokens ->
                    server.accept().use { client ->
                        client.soTimeout = 20_000
                        val reader = client.getInputStream().bufferedReader(Charsets.ISO_8859_1)
                        assertEquals("POST /responses HTTP/1.1", reader.readLine())
                        val headers = generateSequence { reader.readLine()?.takeIf(String::isNotEmpty) }
                            .associate { it.substringBefore(':').lowercase() to it.substringAfter(':').trim() }
                        assertEquals("Bearer loopback-test-token", headers["authorization"])
                        val bytes = ByteArray(headers.getValue("content-length").toInt()) {
                            val next = reader.read()
                            check(next >= 0)
                            next.toByte()
                        }
                        received += JSONObject(bytes.toString(Charsets.UTF_8))
                        val writer = client.getOutputStream().bufferedWriter(Charsets.UTF_8)
                        writer.write("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nConnection: close\r\n\r\n")
                        writer.event(JSONObject().put("type","response.reasoning_text.delta").put("delta",thought))
                        writer.event(JSONObject().put("type","response.output_text.delta").put("delta",partial))
                        check(allowCompletion[index].await(45, TimeUnit.SECONDS))
                        writer.event(JSONObject().put("type","response.output_text.delta").put("delta","。"))
                        writer.event(JSONObject().put("type","response.completed").put("response",
                            JSONObject().put("status","completed").put("usage",JSONObject().put("total_tokens",tokens))))
                    }
                }
            } catch (failure: Throwable) {
                serverFailure.set(failure)
            }
        }
        try {
            HttpsURLConnection.setDefaultSSLSocketFactory(tls.socketFactory)
            HttpsURLConnection.setDefaultHostnameVerifier { host, session ->
                host == "127.0.0.1" && session.peerCertificates.firstOrNull() == cert
            }
            screen.runOnIdle {
                providers.save(deepSeek.copy(baseUrl = "https://127.0.0.1:${server.localPort}", enabled = true),
                    "loopback-test-token", null)
                providers.configure(Mode.Chat, "deepseek-flash", emptyMap())
                vm.newConversation()
                chat = vm.state.value.current.id
            }
            for ((index, tokens) in totals.withIndex()) {
                val prompt = if (index == 0) "all" else "follow-up"
                screen.onNodeWithContentDescription("消息输入框").performClick().performTextInput(prompt)
                screen.onNodeWithContentDescription("发送消息").assertIsEnabled().performSemanticsAction(SemanticsActions.OnClick)
                screen.waitUntil(40_000) {
                    serverFailure.get() != null || vm.state.value.current.messages.lastOrNull()?.let {
                        it.status == Generation.Error ||
                            it.status == Generation.Generating && it.thought == thought && it.text == partial
                    } == true
                }
                serverFailure.get()?.let { throw AssertionError("Loopback server failed", it) }
                val streaming = vm.state.value.current.messages.last()
                assertEquals(streaming.error, Generation.Generating, streaming.status)
                assertEquals(partial, streaming.text)
                allowCompletion[index].countDown()
                screen.waitUntil(20_000) {
                    vm.state.value.current.messages.last().status in listOf(Generation.Completed, Generation.Error)
                }
                val reply = vm.state.value.current.messages.last()
                assertEquals(reply.error, Generation.Completed, reply.status)
                assertEquals(full, reply.text)
                assertEquals(thought, reply.thought)
                assertEquals(tokens, reply.totalTokens)
                screen.onNodeWithText("$tokens tokens").assertIsDisplayed()
                val ring = screen.onNodeWithTag("context-usage-ring", useUnmergedTree = true).assertIsDisplayed()
                assertEquals(tokens / 1_000_000f,
                    ring.fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo].current, .00001f)
                assertInk(ring, blue = index == 0)
                if (index == 0) assertInk(screen.onNodeWithContentDescription("DeepSeek", useUnmergedTree = true), blue = true)
            }
            worker.join(5_000)
            serverFailure.get()?.let { throw AssertionError("Loopback server failed", it) }
            assertEquals(2, received.size)
            assertTrue(received.all { it.getBoolean("stream") && it.getString("model") == "deepseek-flash" })
            assertTrue(received.first().getJSONArray("input").toString().contains("all"))
            assertTrue(received.last().getJSONArray("input").toString().contains(full))
        } finally {
            allowCompletion.forEach { it.countDown() }
            server.close()
            worker.join(5_000)
            HttpsURLConnection.setDefaultSSLSocketFactory(previousFactory)
            HttpsURLConnection.setDefaultHostnameVerifier(previousVerifier)
            store.deleteEntry(alias)
            screen.runOnIdle {
                if (chat.isNotEmpty()) vm.deleteConversation(chat)
                providers.save(deepSeek, "", null)
                providers.configure(Mode.Chat, previousSelection.id, catalog.overrides[previousSelection.id].orEmpty())
            }
        }
    }

    private fun assertInk(node: SemanticsNodeInteraction, blue: Boolean) {
        val bitmap = node.assertIsDisplayed().captureToImage().asAndroidBitmap()
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        assertTrue("Expected visible ${if (blue) "blue" else "red"} ink", pixels.count { pixel ->
            val difference = Color.blue(pixel) - Color.red(pixel)
            if (blue) difference > 40 else difference < -40
        } > 4)
    }

    private fun BufferedWriter.event(payload: JSONObject) {
        write("data: $payload\n\n")
        flush()
    }

    private class FixtureKey(val alias: String, val cert: X509Certificate, val key: PrivateKey) : X509KeyManager {
        override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?) = null
        override fun chooseClientAlias(types: Array<out String>?, issuers: Array<out Principal>?, socket: Socket?) = null
        override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?) =
            if (keyType == "RSA") arrayOf(alias) else null
        override fun chooseServerAlias(keyType: String?, issuers: Array<out Principal>?, socket: Socket?) =
            getServerAliases(keyType, issuers)?.firstOrNull()
        override fun getCertificateChain(alias: String?) = if (alias == this.alias) arrayOf(cert) else null
        override fun getPrivateKey(alias: String?) = if (alias == this.alias) key else null
    }
}
