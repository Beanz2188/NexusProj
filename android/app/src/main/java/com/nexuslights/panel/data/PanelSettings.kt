package com.nexuslights.panel.data

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.io.File

/** How the panel notices someone is there (to wake the screen). */
enum class PresenceMode(val label: String, val detail: String) {
    AUTO("Auto", "Camera while charging, motion sensors on battery"),
    CAMERA("Camera", "Wakes when someone walks up. Uses the most power"),
    SENSORS("Sensors only", "Wakes on a knock, bump or hand wave. Lowest power"),
    OFF("Off", "Touch to wake");
}

/** Panel-only presentation of a bridge light. */
data class LightCustom(val room: String, val icon: String)

class PanelSettings(context: Context) {
    private val prefs = context.getSharedPreferences("panel", Context.MODE_PRIVATE)

    private fun <T> flow(initial: T) = MutableStateFlow(initial)

    // ---- bridge connection -----------------------------------------------------

    /** e.g. http://192.168.70.104:8080, the Mac bridge's dashboard port. */
    private val _bridgeUrl = flow(prefs.getString("bridgeUrl", "") ?: "")
    val bridgeUrl: StateFlow<String> = _bridgeUrl.asStateFlow()
    fun setBridgeUrl(v: String) { _bridgeUrl.value = v; prefs.edit().putString("bridgeUrl", v).apply() }

    private val _token = flow(prefs.getString("token", "") ?: "")
    val token: StateFlow<String> = _token.asStateFlow()
    fun setToken(v: String) { _token.value = v; prefs.edit().putString("token", v).apply() }

    // ---- presence & display --------------------------------------------------------

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

    /** Name shown at the top of the screen. */
    private val _homeName = flow(prefs.getString("homeName", "Home") ?: "Home")
    val homeName: StateFlow<String> = _homeName.asStateFlow()
    fun setHomeName(v: String) { _homeName.value = v; prefs.edit().putString("homeName", v).apply() }

    // ---- per-light room / icon (bridge light id -> choice) -----------------------------

    private val _customizations = flow(loadCustom())
    val customizations: StateFlow<Map<String, LightCustom>> = _customizations.asStateFlow()

    fun setCustomization(id: String, c: LightCustom) {
        val next = _customizations.value + (id to c)
        _customizations.value = next
        val o = JSONObject()
        next.forEach { (k, v) -> o.put(k, JSONObject().put("room", v.room).put("icon", v.icon)) }
        prefs.edit().putString("custom", o.toString()).apply()
    }

    private fun loadCustom(): Map<String, LightCustom> = runCatching {
        val o = JSONObject(prefs.getString("custom", "{}") ?: "{}")
        o.keys().asSequence().associateWith { k ->
            val v = o.getJSONObject(k)
            LightCustom(v.optString("room", "Home"), v.optString("icon", LightIcon.STRIP.id))
        }
    }.getOrDefault(emptyMap())

    /**
     * scripts/provision.sh pushes {"bridge_url": ..., "token": ...} to the
     * app's external files dir (adb can write it, no storage permission
     * needed). Read once, then deleted: it holds the token.
     */
    fun importProvisioning(ctx: Context) {
        val f = File(ctx.getExternalFilesDir(null) ?: return, "panel.json")
        if (!f.exists()) return
        runCatching {
            val o = JSONObject(f.readText())
            o.optString("bridge_url").takeIf { it.isNotBlank() }?.let { setBridgeUrl(it) }
            o.optString("token").takeIf { it.isNotBlank() }?.let { setToken(it) }
            o.optString("home_name").takeIf { it.isNotBlank() }?.let { setHomeName(it) }
            Log.i("PanelSettings", "imported bridge settings for ${bridgeUrl.value}")
        }.onFailure { Log.e("PanelSettings", "bad panel.json", it) }
        f.delete()
    }
}
