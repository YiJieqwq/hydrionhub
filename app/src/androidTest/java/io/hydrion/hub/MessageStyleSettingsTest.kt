package io.hydrion.hub

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.hydrion.hub.ui.messages.MessageStyle
import io.hydrion.hub.ui.messages.MessageStyleStore
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MessageStyleSettingsTest {
    @get:Rule
    val screen = createAndroidComposeRule<MainActivity>()

    private fun control(label: String) = screen.onNode(hasClickAction() and hasText(label))

    @Test
    fun editSeparatePalettesAndRestoreDefaults() {
        val store = MessageStyleStore.shared(screen.activity.applicationContext)
        val previous = store.style.value
        try {
            screen.onNodeWithContentDescription("打开侧栏").performClick()
            screen.onNodeWithTag("drawer-settings").performClick()
            control(screen.activity.getString(io.hydrion.hub.R.string.settings_style)).performScrollTo().performClick()
            control("重置").performClick()
            control("纯色").assertIsSelected()
            control("默认").performScrollTo().performClick().assertIsSelected()
            screen.onNodeWithText("跟随主题配色").performScrollTo().assertIsDisplayed()
            screen.onAllNodes(hasClickAction() and hasText("背景色")).assertCountEquals(0)
            control("显示助手正文气泡").assertIsOff()
            control("磨砂玻璃").performScrollTo().performClick().assertIsSelected()
            control("显示助手正文气泡").performScrollTo().performClick().assertIsOn()

            control("浅色").performScrollTo().performClick()
            control("助手").performScrollTo().performClick()
            control("背景色").performScrollTo().performClick()
            val color = screen.onNode(hasSetTextAction() and hasText("背景色值"))
            color.performScrollTo().performTextReplacement("#INVALID")
            assertEquals("#F0F1F2", store.style.value.light.assistant.background)
            color.performTextReplacement("#98bcde")
            assertEquals("#98BCDE", store.style.value.light.assistant.background)
            assertEquals("#E5EBFF", store.style.value.light.user.background)
            assertEquals("#242628", store.style.value.dark.assistant.background)
            color.performImeAction()
            screen.onNodeWithContentDescription("返回").performClick()

            control(screen.activity.getString(io.hydrion.hub.R.string.settings_style)).performScrollTo().performClick()
            control("磨砂玻璃").assertIsSelected()
            control("显示助手正文气泡").assertIsOn()
            control("助手").performScrollTo().assertIsSelected()
            screen.onNode(hasText("背景色") and hasText("#98BCDE")).assertExists()
            val persisted = MessageStyleStore(screen.activity.applicationContext).style.value
            assertEquals(store.style.value, persisted)

            control("默认").performScrollTo().performClick()
            control("纯色").performScrollTo().performClick()
            screen.onNode(hasText("背景色") and hasText("#98BCDE")).assertExists()
            control("重置").performClick()
            assertEquals(MessageStyle(), store.style.value)
            control("纯色").performScrollTo().assertIsSelected()
            control("用户").performScrollTo().assertIsSelected()
        } finally {
            store.update { previous }
        }
    }
}
