package com.nexuslights.panel.data

/**
 * A light as the Mac's bridge reports it (GET /api/lights), plus the
 * panel-only bits (room, icon) that live on the tablet.
 */
data class LightConfig(
    /** The bridge's HomeKit accessory id, stable across bridge restarts. */
    val id: String,
    val name: String,
    val protocol: String,
    val supportsColor: Boolean = true,
    val room: String = "Home",
    val icon: String = LightIcon.STRIP.id,
)

data class LightState(
    val on: Boolean = false,
    /** 0..100 */
    val brightness: Int = 100,
    /** 0..360 */
    val hue: Float = 32f,
    /** 0..1 (the bridge speaks HomeKit's 0..100) */
    val saturation: Float = 0.55f,
) {
    fun rgb(): Triple<Int, Int, Int> = ColorMath.hsvToRgb(hue, saturation, brightness / 100f)

    /** Full-value color, for UI tinting regardless of brightness. */
    fun displayRgb(): Triple<Int, Int, Int> = ColorMath.hsvToRgb(hue, saturation, 1f)
}

enum class LinkStatus {
    /** The bridge has a live link to the light. */
    CONNECTED,
    /** The bridge connects per command (on-demand light): normal, not an error. */
    IDLE,
    /** The bridge reports the light disconnected. */
    FAILED,
    /** We can't reach the bridge, so we don't know. */
    UNKNOWN,
}

data class Light(
    val config: LightConfig,
    val state: LightState,
    val link: LinkStatus,
)

/** A bridge switch such as TV Ambilight, Rainbow or Music Art Sync. */
data class ModeSwitch(val id: String, val name: String, val on: Boolean)

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

        /** Best guess from the light's name, until the user picks one. */
        fun guess(name: String): LightIcon {
            val n = name.lowercase()
            return when {
                "tv" in n || "ambi" in n -> TV
                "bed" in n -> BED
                "desk" in n || "monitor" in n -> DESK
                "lamp" in n -> LAMP
                "ceiling" in n || "kitchen" in n || "hall" in n -> CEILING
                "bulb" in n -> BULB
                else -> STRIP
            }
        }
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

/** HSV (as used by HomeKit and the UI) -> 0..255 RGB. */
object ColorMath {
    fun hsvToRgb(hueDeg: Float, sat: Float, value: Float): Triple<Int, Int, Int> {
        val h = ((hueDeg % 360f) + 360f) % 360f / 60f
        val s = sat.coerceIn(0f, 1f)
        val v = value.coerceIn(0f, 1f)
        val i = h.toInt()
        val f = h - i
        val p = v * (1 - s)
        val q = v * (1 - s * f)
        val t = v * (1 - s * (1 - f))
        val (r, g, b) = when (i % 6) {
            0 -> Triple(v, t, p)
            1 -> Triple(q, v, p)
            2 -> Triple(p, v, t)
            3 -> Triple(p, q, v)
            4 -> Triple(t, p, v)
            else -> Triple(v, p, q)
        }
        return Triple(Math.round(r * 255), Math.round(g * 255), Math.round(b * 255))
    }
}
