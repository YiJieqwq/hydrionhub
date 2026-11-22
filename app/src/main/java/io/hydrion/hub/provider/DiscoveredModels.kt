package io.hydrion.hub.provider

import java.util.UUID

/** Keep saved routes, profiles, and disabled models when merging a provider's external IDs. */
internal fun addDiscoveredModels(provider: Provider, selectedIds: Collection<String>): List<ProviderModel> {
    val configuredIds = provider.models.mapTo(mutableSetOf()) { it.externalModelId.trim() }
    val names = provider.models.mapTo(sortedSetOf(String.CASE_INSENSITIVE_ORDER)) { it.name.trim() }
    val additions = buildList {
        for (id in selectedIds) {
            if (id.isBlank() || !configuredIds.add(id)) continue
            var name = id
            var suffix = 2
            while (!names.add(name)) name = "$id (${suffix++})"
            add(ProviderModel(UUID.randomUUID().toString(), name,
                profile = ModelProfile(type = ModelType.Chat, externalId = id, output = setOf("text"))))
        }
    }
    return if (additions.isEmpty()) provider.models else provider.models + additions
}
