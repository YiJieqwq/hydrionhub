package io.hydrion.hub.ui.messages

import android.content.Context
import io.hydrion.hub.model.AppRestoreStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

enum class MessageTone { Light, Dark }
enum class CardMaterial { Default, Frosted, Solid }

data class MessageCardStyle(val background: String, val border: String, val text: String)
data class MessagePalette(val assistant: MessageCardStyle, val user: MessageCardStyle)

fun defaultMessagePalette(tone: MessageTone): MessagePalette = when (tone) {
    MessageTone.Dark -> MessagePalette(
        assistant = MessageCardStyle("#242628", "#3B3F44", "#FFFFFF"),
        user = MessageCardStyle("#020562", "#030776", "#D3F0FF")
    )
    MessageTone.Light -> MessagePalette(
        assistant = MessageCardStyle("#F0F1F2", "#C8CBD0", "#202124"),
        user = MessageCardStyle("#E5EBFF", "#C4CFF2", "#182956")
    )
}

private val neutralDark = MessagePalette(
    MessageCardStyle("#222222", "#414141", "#F5F5F5"),
    MessageCardStyle("#303030", "#414141", "#F5F5F5")
)
private val neutralLight = MessagePalette(
    MessageCardStyle("#F0F1F2", "#C8CBD0", "#202124"),
    MessageCardStyle("#E3E4E7", "#C1C3C8", "#202124")
)

data class MessageStyle(
    val tone: MessageTone = MessageTone.Dark,
    val dark: MessagePalette = defaultMessagePalette(MessageTone.Dark),
    val light: MessagePalette = defaultMessagePalette(MessageTone.Light),
    val assistantBubble: Boolean = false,
    val material: CardMaterial = CardMaterial.Solid
) {
    val palette: MessagePalette get() = when (tone) {
        MessageTone.Dark -> dark
        MessageTone.Light -> light
    }

    // Default follows the timeline theme. Switching material never overwrites either edited palette.
    val renderPalette: MessagePalette get() = if (material != CardMaterial.Default) palette else when (tone) {
        MessageTone.Dark -> neutralDark
        MessageTone.Light -> neutralLight
    }

    fun withPalette(tone: MessageTone, palette: MessagePalette): MessageStyle = when (tone) {
        MessageTone.Dark -> copy(dark = palette)
        MessageTone.Light -> copy(light = palette)
    }
}

private val sixHexDigits = Regex("[0-9a-fA-F]{6}")

fun normalizeMessageColor(value: String): String? {
    val digits = value.trim().removePrefix("#")
    return if (sixHexDigits.matches(digits)) "#" + digits.uppercase(Locale.ROOT) else null
}

internal fun MessageStyle.toPreferences(): Map<String, String> = buildMap {
    put("tone", tone.name)
    put("material", material.name)
    put("assistantBubble", assistantBubble.toString())
    fun putCard(prefix: String, card: MessageCardStyle) {
        put("$prefix.background", card.background)
        put("$prefix.border", card.border)
        put("$prefix.text", card.text)
    }
    putCard("dark.assistant", dark.assistant)
    putCard("dark.user", dark.user)
    putCard("light.assistant", light.assistant)
    putCard("light.user", light.user)
}

internal fun readMessageStyle(fields: Map<String, *>, fallback: MessageStyle = MessageStyle()): MessageStyle {
    fun color(key: String, previous: String): String =
        (fields[key] as? String)?.let(::normalizeMessageColor) ?: previous
    fun card(prefix: String, previous: MessageCardStyle) = MessageCardStyle(
        color("$prefix.background", previous.background), color("$prefix.border", previous.border),
        color("$prefix.text", previous.text)
    )
    fun palette(prefix: String, previous: MessagePalette) = MessagePalette(
        card("$prefix.assistant", previous.assistant), card("$prefix.user", previous.user)
    )
    return MessageStyle(
        tone = MessageTone.entries.find { it.name == fields["tone"] } ?: fallback.tone,
        material = CardMaterial.entries.find { it.name == fields["material"] } ?: fallback.material,
        assistantBubble = (fields["assistantBubble"] as? String)?.toBooleanStrictOrNull() ?: fallback.assistantBubble,
        dark = palette("dark", fallback.dark), light = palette("light", fallback.light)
    )
}

class MessageStyleStore(context: Context) {
    private val location = AppRestoreStore.current(context)
    private val preferences = context.applicationContext.getSharedPreferences(location.preference("message_style"), Context.MODE_PRIVATE)
    private val mutable = MutableStateFlow(readMessageStyle(preferences.all.ifEmpty {
        location.initialSettings()?.appearance?.toPreferences().orEmpty()
    }))
    val style: StateFlow<MessageStyle> = mutable.asStateFlow()

    @Synchronized
    fun update(change: (MessageStyle) -> MessageStyle) {
        val previous = mutable.value
        val next = readMessageStyle(change(previous).toPreferences(), previous)
        if (next == previous) return
        val edit = preferences.edit()
        next.toPreferences().forEach { (key, value) -> edit.putString(key, value) }
        edit.apply()
        mutable.value = next
    }

    companion object {
        @Volatile private var instance: MessageStyleStore? = null
        internal fun shared(context: Context): MessageStyleStore = instance ?: synchronized(this) {
            instance ?: MessageStyleStore(context).also { instance = it }
        }
    }
}
