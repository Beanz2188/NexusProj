package com.nexuslights.panel.data

import com.nexuslights.panel.protocol.ColorMath
import com.nexuslights.panel.protocol.LightProtocol

/** What the user configured: one physical strip. */
data class LightConfig(
    val address: String,
    val name: String,
    val protocol: String,
    val room: String = "Home",
    val icon: String = LightIcon.STRIP.id,
) {
    val protocolImpl: LightProtocol get() = LightProtocol.byId(protocol)
}

/**
 * Last commanded state. The strips are write-only (no state read-back), so
 * this app is the source of truth and persists it across restarts.
 */
data class LightState(
    val on: Boolean = false,
    /** 1..100 */
    val brightness: Int = 100,
    /** 0..360 */
    val hue: Float = 32f,
    /** 0..1 */
    val saturation: Float = 0.55f,
) {
    fun rgb(): Triple<Int, Int, Int> = ColorMath.hsvToRgb(hue, saturation, brightness / 100f)

    /** Full-value color, for UI tinting regardless of brightness. */
    fun displayRgb(): Triple<Int, Int, Int> = ColorMath.hsvToRgb(hue, saturation, 1f)
}

enum class LinkStatus { DISCONNECTED, CONNECTING, CONNECTED, FAILED }

data class Light(
    val config: LightConfig,
    val state: LightState,
    val link: LinkStatus,
)

enum class LightIcon(val id: String, val label: String) {
    STRIP("strip", "Light strip"),
    BULB("bulb", "Bulb"),
    LAMP("lamp", "Lamp"),
    CEILING("ceiling", "Ceiling"),
    TV("tv", "TV backlight"),
    BED("bed", "Bed"),
    DESK("desk", "Desk");

    companion object {
        fun of(id: String) = entries.firstOrNull { it.id == id } ?: STRIP
    }
}

data class Scene(
    val id: String,
    val name: String,
    val icon: String,
    /** null = turn everything off */
    val state: LightState?,
)

object Scenes {
    val ALL = listOf(
        Scene("off", "All Off", "power", null),
        Scene("bright", "Bright", "sun", LightState(on = true, brightness = 100, hue = 40f, saturation = 0.08f)),
        Scene("relax", "Relax", "sofa", LightState(on = true, brightness = 55, hue = 28f, saturation = 0.7f)),
        Scene("focus", "Focus", "focus", LightState(on = true, brightness = 90, hue = 200f, saturation = 0.12f)),
        Scene("movie", "Movie", "movie", LightState(on = true, brightness = 22, hue = 245f, saturation = 0.85f)),
        Scene("night", "Night", "moon", LightState(on = true, brightness = 6, hue = 18f, saturation = 0.95f)),
    )
}
