package io.hydrion.hub.ui.sidebar

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.hydrion.hub.model.Conversation
import io.hydrion.hub.ui.messages.searchableBody

internal data class DrawerSearchState(
    val query: String = "",
    val global: Boolean = false,
    val submitted: String? = null,
    val selectedResultId: String? = null,
) : java.io.Serializable {
    fun toggleScope() = DrawerSearchState(global = !global)

    fun edit(text: String) = copy(
        query = text,
        submitted = null,
        selectedResultId = if (text.isBlank()) null else selectedResultId,
    )

    fun submit() = copy(submitted = query.trim().ifEmpty { null })
    fun selectResult(id: String) = copy(selectedResultId = id)
}

internal data class DrawerHit(val id: String, val title: String, val snippet: String, val messageId: String?,
    val needle: String?, val score: Long, val updatedAt: Long)

internal fun globalDrawerHits(history: List<Conversation>, query: String): List<DrawerHit> {
    val terms = query.trim().split(Regex("\\s+")).filter(String::isNotBlank)
        .groupBy { it.lowercase() }.values.associate { it.first() to it.size }
    if (terms.isEmpty()) return emptyList()
    return history.mapNotNull { item ->
        if (item.archived) return@mapNotNull null
        val found = mutableSetOf<String>()
        var score = 0L
        var snippet = ""
        var messageId: String? = null
        var needle: String? = null

        fun scoreText(text: String, weight: Long, sourceMessageId: String? = null) {
            var earliest = Int.MAX_VALUE
            var firstToken: String? = null
            for ((token, repetitions) in terms) {
                var index = text.indexOf(token, ignoreCase = true)
                if (index < 0) continue
                found += token
                if (index < earliest) {
                    earliest = index
                    firstToken = token
                }
                val points = weight * repetitions
                do {
                    score += points
                    index = text.indexOf(token, index + token.length, ignoreCase = true)
                } while (index >= 0)
            }
            if (sourceMessageId != null && messageId == null && firstToken != null) {
                messageId = sourceMessageId
                needle = firstToken
                val start = (earliest - 30).coerceAtLeast(0)
                val end = start + minOf(160, text.length - start)
                snippet = (if (start > 0) "…" else "") + text.substring(start, end).replace('\n', ' ').trim()
            }
        }

        scoreText(item.title, weight = 3L)
        for (message in item.messages) {
            scoreText(message.searchableBody(), weight = 1L, sourceMessageId = message.id)
            if (message.imageKeys.isNotEmpty()) {
                scoreText(message.prompt, weight = 1L, sourceMessageId = message.id)
            }
        }
        if (found.size == terms.size) DrawerHit(item.id, item.title, snippet, messageId, needle, score, item.updatedAt) else null
    }.sortedWith(compareByDescending<DrawerHit> { it.score }.thenByDescending { it.updatedAt }).take(200)
}

@Composable
internal fun DrawerSearchResult(hit: DrawerHit, tokens: List<String>, selected: Boolean, open: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val background = animateColorAsState(
        if (selected) colors.primary.copy(alpha = .16f) else Color.Transparent,
        tween(120), label = "Search result selection",
    )
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
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(background.value)
        .clickable(onClick = open).semantics { this.selected = selected }
        .heightIn(min = 48.dp).padding(horizontal = 14.dp, vertical = 9.dp)) {
        Text(marked(hit.title), fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (hit.snippet.isNotEmpty()) Text(marked(hit.snippet), fontSize = 12.5.sp, lineHeight = 17.5.sp,
            color = colors.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}
