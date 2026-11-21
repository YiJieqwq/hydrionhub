package io.hydrion.hub.ui.sidebar

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.hydrion.hub.model.Conversation

internal data class DrawerHit(val id: String, val title: String, val snippet: String, val messageId: String?,
    val needle: String?, val score: Int, val updatedAt: Long)

internal fun globalDrawerHits(history: List<Conversation>, query: String): List<DrawerHit> {
    val tokens = query.trim().split(Regex("\\s+")).filter(String::isNotBlank).distinctBy { it.lowercase() }
    if (tokens.isEmpty()) return emptyList()
    return history.mapNotNull { item ->
        val titleMatches = tokens.filter { item.title.contains(it, ignoreCase = true) }
        val found = titleMatches.toMutableSet()
        var score = titleMatches.size * 4
        var snippet = ""
        var messageId: String? = null
        var needle: String? = null
        for (message in item.messages) {
            val surfaces = if (message.imageKeys.isEmpty()) listOf(message.text) else listOf(message.text, message.prompt)
            for (surface in surfaces) {
                val matches = tokens.filter { surface.contains(it, ignoreCase = true) }
                if (matches.isEmpty()) continue
                found += matches
                score += matches.size
                if (messageId == null) {
                    messageId = message.id
                    val first = matches.minBy { surface.indexOf(it, ignoreCase = true) }
                    needle = first
                    val start = (surface.indexOf(first, ignoreCase = true) - 30).coerceAtLeast(0)
                    snippet = (if (start > 0) "…" else "") + surface.substring(start, start + minOf(160, surface.length - start)).replace('\n', ' ').trim()
                }
            }
        }
        if (found.size == tokens.size) DrawerHit(item.id, item.title, snippet, messageId, needle, score, item.updatedAt) else null
    }.sortedWith(compareByDescending<DrawerHit> { it.score }.thenByDescending { it.updatedAt }).take(200)
}

@Composable
internal fun DrawerSearchResult(hit: DrawerHit, tokens: List<String>, open: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val highlight = SpanStyle(background = colors.primary.copy(alpha = .20f))
    fun marked(value: String) = buildAnnotatedString {
        append(value)
        for (token in tokens.filter(String::isNotEmpty)) {
            var start = value.indexOf(token, ignoreCase = true)
            while (start >= 0) {
                addStyle(highlight, start, start + token.length)
                start = value.indexOf(token, start + token.length, ignoreCase = true)
            }
        }
    }
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable(onClick = open)
        .heightIn(min = 48.dp).padding(horizontal = 14.dp, vertical = 9.dp)) {
        Text(marked(hit.title), fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (hit.snippet.isNotEmpty()) Text(marked(hit.snippet), fontSize = 12.5.sp, lineHeight = 17.5.sp,
            color = colors.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}
