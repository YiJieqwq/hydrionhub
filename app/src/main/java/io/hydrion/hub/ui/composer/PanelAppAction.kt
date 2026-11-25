package io.hydrion.hub.ui.composer

import android.content.ContentUris
import android.content.Intent
import android.net.Uri
import android.provider.CalendarContract
import java.net.URI

/** These intents are launched only after a manual confirmation in the current conversation. */
internal enum class PanelAppAction {
    CreateEvent, ViewEvents, Browser;

    fun intent(now: Long = System.currentTimeMillis(), address: String = ""): Intent? = when (this) {
        CreateEvent -> Intent(Intent.ACTION_INSERT).setDataAndType(CalendarContract.Events.CONTENT_URI, "vnd.android.cursor.dir/event")
        ViewEvents -> Intent(Intent.ACTION_VIEW, ContentUris.withAppendedId(CalendarContract.CONTENT_URI.buildUpon()
            .appendPath("time").build(), now))
        Browser -> {
            val uri = address.trim().takeIf { it.length in 1..2048 }?.let { runCatching { URI(it) }.getOrNull() }
            if (uri == null || !uri.scheme.equals("https", true) || uri.host.isNullOrBlank() || uri.rawUserInfo != null ||
                (uri.port != -1 && uri.port !in 1..65535)) null
            else Intent(Intent.ACTION_VIEW, Uri.parse(uri.toASCIIString()).normalizeScheme()).addCategory(Intent.CATEGORY_BROWSABLE)
        }
    }
}
