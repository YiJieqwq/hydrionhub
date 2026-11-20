package io.hydrion.hub.provider

import io.hydrion.hub.model.Mode
import org.json.JSONObject

/** Stored choices the selected chat transport actually consumes. */
internal data class ChatSpeedControls(
    val fast: String? = null,
    val ultra: String? = null,
    val off: String? = null,
    val selected: String? = null,
)

internal fun ProviderModel.chatSpeedControls(provider: Provider, values: Map<String, String>): ChatSpeedControls {
    val none = ChatSpeedControls()
    if (provider.mode != Mode.Chat || provider.chatProtocol() != null || provider.chatProtocolIssue() != null) return none
    val speed = configureCapabilities().find { it.key == "speed" } ?: return none
    // LocalModelRequest applies scalar request-body additions after the per-model choices.
    val body = profile.requestBody
    val override = if (body.isBlank()) null else {
        val json = runCatching { JSONObject(body) }.getOrNull() ?: return none
        json.opt("speed")?.takeIf { it is String || it is Number || it is Boolean }?.toString()
    }
    val value = override ?: values["speed"] ?: speed.choices.first()
    val fast = "Fast".takeIf { it in speed.choices || value == it }
    val off = speed.choices.firstOrNull { it.equals("Standard", true) || it.equals("Off", true) ||
        it.equals("Normal", true) || it.equals("Default", true) }
    // The local text simulator has one accelerated cadence. Online transports have no speed mapping.
    return ChatSpeedControls(fast = fast, off = off.takeIf { override == null }, selected = value.takeIf { it == fast })
}
