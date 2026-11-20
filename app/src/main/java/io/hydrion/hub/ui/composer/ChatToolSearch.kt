package io.hydrion.hub.ui.composer

/** A matching group keeps its full set of options; otherwise only named matches remain. */
internal fun <T> matchingToolChoices(query: String, group: String, choices: List<T>, label: (T) -> String): List<T> {
    val term = query.trim()
    if (term.isEmpty() || group.contains(term, ignoreCase = true)) return choices
    return choices.filter { label(it).contains(term, ignoreCase = true) }
}
