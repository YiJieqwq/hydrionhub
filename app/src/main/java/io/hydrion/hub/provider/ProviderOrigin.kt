package io.hydrion.hub.provider

import java.net.URI
import java.util.Locale

/** The HTTP origin that receives a provider's credentials, independent of its API path. */
internal fun Provider.credentialOrigin(): String? {
    val base = baseUrl.trim().ifEmpty { if (id == DEEPSEEK_PROVIDER_ID) DEEPSEEK_BASE_URL else return null }
    val uri = runCatching { URI(base) }.getOrNull() ?: return null
    if (uri.rawUserInfo != null || uri.rawQuery != null || uri.rawFragment != null) return null
    val scheme = when (uri.scheme?.lowercase(Locale.ROOT)) {
        "https", "wss" -> "https"
        "http" -> "http"
        else -> return null
    }
    val host = uri.host?.lowercase(Locale.ROOT) ?: return null
    val port = when (uri.port) {
        -1 -> if (scheme == "https") 443 else 80
        in 1..65535 -> uri.port
        else -> return null
    }
    return "$scheme://$host:$port"
}

internal fun Provider.withoutInheritedHeaders(previous: Provider): Provider {
    if (credentialOrigin() == previous.credentialOrigin()) return this
    val previousValues = previous.models.flatMap { it.profile.headers.values }
        .map { it.trim() }.filter { it.isNotEmpty() }.toSet()
    if (previousValues.isEmpty()) return this

    return copy(models = models.map { model ->
        val profile = model.profile
        val headers = profile.headers.mapValues { (_, value) ->
            if (value.trim() in previousValues) "" else value
        }
        model.copy(profile = profile.copy(headers = headers))
    })
}
