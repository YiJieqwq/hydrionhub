package io.hydrion.hub.provider

import androidx.compose.ui.graphics.Color
import java.util.Locale

internal data class EffortPalette(
    val fill: Color,
    val ink: Color,
    val track: Color,
    val tick: Color,
    val passedTick: Color,
    val thumb: Color,
    val mote: Color,
    val isPeak: Boolean,
)

private enum class EffortTone(
    val lightFill: Int,
    val lightInk: Int,
    val darkFill: Int,
    val darkInk: Int,
) {
    Off(0x8a8a8f, 0x707073, 0x737378, 0xa9a9ae),
    Minimal(0x4a8ef8, 0x2b62bd, 0x3684ff, 0x73a9ff),
    Low(0x3a83f7, 0x2f6fd0, 0x448dff, 0x76aafe),
    Medium(0x4a72f2, 0x3a55b8, 0x6e90ff, 0x8ba7ff),
    High(0x5f5ce8, 0x4a3fc0, 0x928fff, 0xa4a2ff),
    ExtraHigh(0x7b48e8, 0x6a35c8, 0xb18dff, 0xbc9cff),
    Max(0x914ef6, 0x8b46ee, 0xbd91ff, 0xc39bff),
}

private val nonStrengthChoices = setOf("", "off", "none", "disabled", "关闭", "无", "auto", "default")

private fun String.paletteKey() = trim().lowercase(Locale.ROOT)

/** Returns the index into the displayed provider choices, or -1 when none merits a peak effect. */
internal fun peakEffortIndex(levels: List<String>): Int {
    val first = levels.indexOfFirst { it.paletteKey() !in nonStrengthChoices }
    val last = levels.indexOfLast { it.paletteKey() !in nonStrengthChoices }
    if (last < 0) return -1
    val key = levels[last].paletteKey()
    return if (last != first || key == "max" || key == "ultra") last else -1
}

/** [levels] contains the displayed provider choices in rail order. A colour never adds a choice. */
internal fun effortPalette(levels: List<String>, level: String?, dark: Boolean): EffortPalette {
    val index = if (level == null) -1 else levels.indexOf(level)
    val key = levels.getOrNull(index).orEmpty().paletteKey()
    val tone = when (key) {
        "", "off", "none", "disabled", "关闭", "无" -> EffortTone.Off
        "minimal" -> EffortTone.Minimal
        "medium", "mid" -> EffortTone.Medium
        "high" -> EffortTone.High
        "xhigh", "extra-high", "extra high" -> EffortTone.ExtraHigh
        "ultra", "max" -> EffortTone.Max
        else -> EffortTone.Low // Low is also the reference colour for provider-defined labels.
    }
    return effortColors(tone, dark, index >= 0 && index == peakEffortIndex(levels))
}

internal fun ordinaryFastRailColors(dark: Boolean): EffortPalette = effortColors(EffortTone.Low, dark, false)

private fun effortColors(tone: EffortTone, dark: Boolean, isPeak: Boolean): EffortPalette {
    val fill = if (dark) tone.darkFill else tone.lightFill
    val thumb = if (dark) Color(0xFFF9FAFB) else Color.White
    return EffortPalette(
        fill = opaqueRgb(fill),
        ink = opaqueRgb(if (dark) tone.darkInk else tone.lightInk),
        track = mixRgb(if (dark) 0x232324 else 0xe8e7e8, fill, 22),
        tick = Color(0xFFB6B6B8),
        passedTick = if (dark) thumb else Color.White.copy(alpha = 0.55f),
        thumb = thumb,
        mote = mixRgb(fill, 0xffffff, if (dark) 30 else 42),
        isPeak = isPeak,
    )
}

private fun opaqueRgb(rgb: Int) = Color(rgb or 0xff000000.toInt())

// The reference mixes encoded sRGB channels. Compose's Color lerp interpolates in Oklab.
private fun mixRgb(start: Int, end: Int, percent: Int): Color {
    fun channel(shift: Int): Int {
        val a = (start shr shift) and 0xff
        val b = (end shr shift) and 0xff
        return (a * (100 - percent) + b * percent + 50) / 100
    }
    return Color(red = channel(16), green = channel(8), blue = channel(0))
}
