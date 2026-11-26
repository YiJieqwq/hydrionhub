package io.hydrion.hub.ui.messages

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RichRenderIsolationTest {
    @Test fun onlyFixedLocalAssetsAreAvailableInTheOfflineWebView() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val view = newRichWebView(context, CompletableDeferred())
        try {
            assertTrue(view.settings.blockNetworkLoads)
            assertFalse(view.settings.allowContentAccess)
            assertFalse(view.settings.allowFileAccess)
            assertFalse(view.settings.domStorageEnabled)
            for (name in listOf("index.html", "render.js", "mermaid.js", "mathjax.js")) {
                val response = richAsset(context, richPage.substringBeforeLast('/') + "/" + name, "GET")
                assertEquals(200, response.statusCode)
                response.data.use { assertTrue(it.read() >= 0) }
            }
            for (url in listOf("https://example.invalid/extra.js", "file:///extra.js", richPage + "?version=1")) {
                assertEquals(403, richAsset(context, url, "GET").statusCode)
            }
            assertEquals(403, richAsset(context, richPage, "POST").statusCode)
        } finally { view.destroy() }
    }
}
