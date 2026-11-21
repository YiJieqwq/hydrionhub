package io.hydrion.hub.ui.settings

import android.content.Context
import android.content.ContextWrapper
import androidx.activity.compose.setContent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import io.hydrion.hub.MainActivity
import io.hydrion.hub.R
import io.hydrion.hub.ui.profile.LocalProfileStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.time.Instant
import java.util.UUID

class SettingsProfileTest {
    @get:Rule val screen = createAndroidComposeRule<MainActivity>()

    @Test fun localProfileNameSurvivesReopeningTheStore() {
        val key = UUID.randomUUID().toString()
        val context = object : ContextWrapper(screen.activity.applicationContext) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String, mode: Int) = baseContext.getSharedPreferences(key, mode)
        }
        try {
            runBlocking { LocalProfileStore(context).save("  Local name  ", null, false) }
            assertEquals("Local name", LocalProfileStore(context).profile.value.name)
        } finally {
            screen.activity.deleteSharedPreferences(key)
        }
    }

    @Test fun cancelDiscardsProfileEdits() {
        val store = LocalProfileStore.shared(screen.activity)
        val original = store.profile.value
        screen.onNodeWithContentDescription("打开侧栏").performClick()
        screen.onNodeWithTag("drawer-settings").performClick()
        screen.onNodeWithTag("settings-profile").performClick()
        screen.onNode(hasSetTextAction() and hasAnyAncestor(isDialog())).performTextReplacement("Discard this")
        screen.onNodeWithText(screen.activity.getString(R.string.settings_cancel)).performClick()
        screen.onNode(isDialog()).assertDoesNotExist()
        assertEquals(original, store.profile.value)
    }

    @Test fun showsOnlyTheSuppliedWindowsAndDistinguishesNoUsageFromZero() {
        val accounts = listOf(
            SignedInAccountUsage("Fixture A", null, listOf(QuotaWindow(300, null, 0.0, Instant.EPOCH))),
            SignedInAccountUsage("Fixture B", null, listOf(QuotaWindow(10080, null, null, null))))
        screen.activityRule.scenario.onActivity { it.setContent { AccountUsage(accounts) } }
        fun label(id: Int) = screen.activity.getString(id)
        screen.onNodeWithText(label(R.string.settings_usage_five)).assertIsDisplayed()
        screen.onNodeWithText(label(R.string.settings_usage_week)).assertIsDisplayed()
        screen.onNodeWithText(label(R.string.settings_usage_empty)).assertIsDisplayed()
        screen.onAllNodes(hasProgressBarRangeInfo(androidx.compose.ui.semantics.ProgressBarRangeInfo(0f, 0f..1f)))
            .assertCountEquals(1)
    }
}
