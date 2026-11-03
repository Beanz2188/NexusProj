package com.nexuslights.panel.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** How the panel notices someone is there (to wake the screen / feed HomeKit). */
enum class PresenceMode(val label: String, val detail: String) {
    AUTO("Auto", "Camera while charging, motion sensors on battery"),
    CAMERA("Camera", "Wakes when someone walks up. Uses the most power"),
    SENSORS("Sensors only", "Wakes on a knock, bump or hand wave. Lowest power"),
    OFF("Off", "Touch to wake");
}

class PanelSettings(context: Context) {
    private val prefs = context.getSharedPreferences("panel", Context.MODE_PRIVATE)

    private fun <T> flow(initial: T) = MutableStateFlow(initial)

    private val _presence = flow(PresenceMode.entries.getOrElse(prefs.getInt("presence", 0)) { PresenceMode.AUTO })
    val presence: StateFlow<PresenceMode> = _presence.asStateFlow()
    fun setPresence(m: PresenceMode) { _presence.value = m; prefs.edit().putInt("presence", m.ordinal).apply() }

    /** Seconds of no touch/motion before the screen dims. */
    private val _idleSeconds = flow(prefs.getInt("idle", 45))
    val idleSeconds: StateFlow<Int> = _idleSeconds.asStateFlow()
    fun setIdleSeconds(s: Int) { _idleSeconds.value = s; prefs.edit().putInt("idle", s).apply() }

    /** Show a dim clock between "active" and "screen off". */
    private val _ambientClock = flow(prefs.getBoolean("ambient", true))
    val ambientClock: StateFlow<Boolean> = _ambientClock.asStateFlow()
    fun setAmbientClock(v: Boolean) { _ambientClock.value = v; prefs.edit().putBoolean("ambient", v).apply() }

    /** 1 (least sensitive) .. 5 (most). */
    private val _sensitivity = flow(prefs.getInt("sensitivity", 3))
    val sensitivity: StateFlow<Int> = _sensitivity.asStateFlow()
    fun setSensitivity(v: Int) { _sensitivity.value = v.coerceIn(1, 5); prefs.edit().putInt("sensitivity", v).apply() }

    /** Auto-adjust screen brightness from the ambient light sensor. */
    private val _autoBrightness = flow(prefs.getBoolean("autoBrightness", true))
    val autoBrightness: StateFlow<Boolean> = _autoBrightness.asStateFlow()
    fun setAutoBrightness(v: Boolean) { _autoBrightness.value = v; prefs.edit().putBoolean("autoBrightness", v).apply() }

    /** Name shown at the top of the screen and as the HomeKit bridge name. */
    private val _homeName = flow(prefs.getString("homeName", "Home") ?: "Home")
    val homeName: StateFlow<String> = _homeName.asStateFlow()
    fun setHomeName(v: String) { _homeName.value = v; prefs.edit().putString("homeName", v).apply() }

    /**
     * Connect to every strip per command instead of holding a link to each.
     * Slower to respond, but works on radios that can't hold many links.
     */
    private val _bleOnDemand = flow(prefs.getBoolean("bleOnDemand", false))
    val bleOnDemand: StateFlow<Boolean> = _bleOnDemand.asStateFlow()
    fun setBleOnDemand(v: Boolean) { _bleOnDemand.value = v; prefs.edit().putBoolean("bleOnDemand", v).apply() }
}
