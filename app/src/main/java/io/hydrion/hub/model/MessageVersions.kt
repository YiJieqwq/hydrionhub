package io.hydrion.hub.model

/** The visible version lives on the timeline. Archived versions share its logical ID and reply links. */
data class MessageVersions(val selected: Int, val others: List<Message>) {
    init { require(others.isNotEmpty() && selected in 0..others.size) }

    fun ordered(visible: Message): List<Message> = others.toMutableList().apply { add(selected, visible) }
}

internal fun Conversation.allStoredMessages() = messages.asSequence() +
    versions.values.asSequence().flatMap { it.others.asSequence() }

internal fun Conversation.appendVersion(previous: Message, replacement: Message): Conversation {
    val history = versions[previous.id]?.ordered(previous) ?: listOf(previous)
    return copy(messages = messages.map { if (it.id == previous.id) replacement else it },
        versions = versions + (previous.id to MessageVersions(history.size, history)))
}

internal fun Conversation.withVersion(id: String, ordinal: Int, deleteCurrent: Boolean = false): Conversation {
    val group = versions[id] ?: return this
    val visible = messages.find { it.id == id } ?: return this
    val ordered = group.ordered(visible).toMutableList()
    if (deleteCurrent) ordered.removeAt(group.selected)
    if (ordinal !in ordered.indices) return this
    val next = ordered.removeAt(ordinal)
    return copy(messages = messages.map { if (it.id == id) next else it },
        versions = if (ordered.isEmpty()) versions - id else versions + (id to MessageVersions(ordinal, ordered)))
}

internal fun Conversation.canRetryChatMessage(message: Message): Boolean {
    val index = messages.indexOfFirst { it.id == message.id }
    if (index < 0) return false
    val start = contextStart?.let { anchor -> messages.indexOfFirst { it.id == anchor } } ?: 0
    if (start < 0 || index < start) return false
    return message.role == Role.User || messages.subList(start, index).any { it.role == Role.User && it.id == message.replyTo }
}

internal fun Conversation.copyThrough(end: Int, name: String, time: Long, keepVersions: Boolean): Conversation {
    val prefix = messages.take(end + 1)
    val ids = prefix.associate { it.id to newId() }
    fun independent(m: Message) = m.copy(id = ids.getValue(m.id), replyTo = ids[m.replyTo])
    val copied = if (!keepVersions) emptyMap() else versions.filterKeys { it in ids }.map { (id, group) ->
        ids.getValue(id) to group.copy(others = group.others.map(::independent))
    }.toMap()
    return copy(id = newId(), title = name, pinned = false, updatedAt = time,
        messages = prefix.map(::independent), contextStart = ids[contextStart], versions = copied)
}

/** Removing a prompt must never orphan or silently remove an unselected reply. */
internal fun Conversation.canDeleteSelection(ids: Set<String>, allVersions: Boolean): Boolean {
    if (mode != Mode.Chat || ids.isEmpty() || messages.any { it.status.isActive }) return false
    val removedGroups = if (allVersions) ids else ids - versions.keys
    return contextStart !in removedGroups && messages.count { it.id in ids } == ids.size &&
        messages.none { it.replyTo in removedGroups && it.id !in removedGroups }
}

internal fun Conversation.deleteSelection(ids: Set<String>, allVersions: Boolean): Conversation {
    if (!canDeleteSelection(ids, allVersions)) return this
    val revised = versions.toMutableMap()
    val surviving = messages.mapNotNull { msg ->
        if (msg.id !in ids) return@mapNotNull msg
        val group = revised.remove(msg.id)
        if (allVersions || group == null) return@mapNotNull null
        val remaining = group.others.toMutableList()
        val ordinal = group.selected.coerceAtMost(remaining.lastIndex)
        val next = remaining.removeAt(ordinal)
        if (remaining.isNotEmpty()) revised[msg.id] = MessageVersions(ordinal, remaining)
        next
    }
    return copy(messages = surviving, versions = revised)
}
