package io.hydrion.hub.ui.messages

import android.content.ActivityNotFoundException
import io.hydrion.hub.R
import java.net.URI
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class MessageAttachmentTargetTest {
    private fun isTarget(raw: String): Boolean {
        val uri = URI(raw)
        return isMessageAttachmentTarget(uri.scheme, uri.authority)
    }

    @Test fun openingAnAttachmentAttemptsOnceAndReportsOnlyTheExpectedFailure() {
        fun checkResult(error: RuntimeException?, expectedNotices: List<Int>) {
            var attempts = 0
            val notices = mutableListOf<Int>()
            openMessageAttachment(launch = {
                attempts++
                if (error != null) throw error
            }, onFailure = { notices += it })
            assertEquals(1, attempts)
            assertEquals(expectedNotices, notices)
        }

        checkResult(null, emptyList())
        checkResult(ActivityNotFoundException(), listOf(R.string.turn_attachment_no_viewer))
        checkResult(SecurityException(), listOf(R.string.turn_attachment_access_denied))
    }

    @Test fun unexpectedAttachmentOpenFailuresPropagate() {
        val cause = IllegalStateException("unexpected failure")
        val thrown = assertThrows(IllegalStateException::class.java) {
            openMessageAttachment(launch = { throw cause }, onFailure = { fail("Unexpected failure notice") })
        }
        assertSame(cause, thrown)
    }

    @Test fun aMissingProviderOrAnotherSchemeCannotOpenAnAttachment() {
        for (raw in listOf("", "relative/notes.txt", "content:notes", "content:///notes",
            "content://10@/notes", "content://%20%20/notes", "file:///notes", "https://fixture.example/notes")) {
            assertFalse(raw, isTarget(raw))
        }
    }

    @Test fun providersContainingWhitespaceOrControlCharactersAreRejected() {
        for (encoded in listOf("%20", "%09", "%0A", "%7F")) {
            val raw = "content://fixture${encoded}docs/document/notes"
            assertFalse(raw, isTarget(raw))
        }
    }

    @Test fun documentProvidersCanHaveUnderscoresAndAndroidUserPrefixes() {
        for (provider in listOf("fixture.docs", "fixture_docs", "10@fixture.docs")) {
            val raw = "content://$provider/document/primary%3Ateam%2Fnotes%20one.txt?view=preview"
            assertTrue(raw, isTarget(raw))
        }
    }
}
