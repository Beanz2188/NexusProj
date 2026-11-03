package com.nexuslights.panel.data

import android.content.Context
import android.util.Log
import com.nexuslights.panel.ble.Cmd
import com.nexuslights.panel.ble.LightLinks
import com.nexuslights.panel.protocol.LightProtocol
import com.nexuslights.panel.system.Backup
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Single source of truth for configured lights and their state. Everything
 * (touch UI, HomeKit, scenes, motion automations) goes through here, so all
 * of them stay in sync and every change is persisted + sent over BLE.
 */
class LightRepository(private val context: Context, private val ble: LightLinks, private val wifi: LightLinks) {

    enum class Source { UI, HOMEKIT, SCENE, AUTOMATION }

    fun interface ChangeListener {
        fun onChanged(address: String, old: LightState?, new: LightState?)
    }

    private val file = File(context.filesDir, "lights.json")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _configs = MutableStateFlow<List<LightConfig>>(emptyList())
    private val _states = MutableStateFlow<Map<String, LightState>>(emptyMap())
    private val listeners = CopyOnWriteArrayList<ChangeListener>()

    val configs: StateFlow<List<LightConfig>> = _configs.asStateFlow()
    val states: StateFlow<Map<String, LightState>> = _states.asStateFlow()

    val lights: StateFlow<List<Light>> = combine(_configs, _states, ble.links, wifi.links) { cfgs, st, bl, wl ->
        cfgs.map { Light(it, st[it.address] ?: LightState(), bl[it.address] ?: wl[it.address] ?: LinkStatus.DISCONNECTED) }
    }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    /** One protocol instance per light: some keep state (LEDnetWF IOTWF's sequence counter). */
    private val protocols = java.util.concurrent.ConcurrentHashMap<String, LightProtocol>()

    private fun protoFor(cfg: LightConfig): LightProtocol {
        val existing = protocols[cfg.address]
        if (existing != null && existing.id == cfg.protocol) return existing
        return LightProtocol.create(cfg.protocol).also { protocols[cfg.address] = it }
    }

    private fun linksFor(cfg: LightConfig): LightLinks =
        if (cfg.protocolInfo.transport == LightProtocol.Transport.WIFI) wifi else ble

    private fun publishDevices() {
        ble.setKnownDevices(_configs.value)
        wifi.setKnownDevices(_configs.value)
    }

    fun retryAll() { ble.retryAll(); wifi.retryAll() }

    init {
        load()
        publishDevices()
    }

    fun addListener(l: ChangeListener) = listeners.add(l)
    fun removeListener(l: ChangeListener) = listeners.remove(l)

    fun config(address: String) = _configs.value.firstOrNull { it.address == address }
    fun state(address: String) = _states.value[address] ?: LightState()

    // ---- configuration ---------------------------------------------------

    @Synchronized
    fun addOrUpdate(cfg: LightConfig) {
        val addr = cfg.address.uppercase()
        val c = cfg.copy(address = addr)
        _configs.update { list ->
            if (list.any { it.address == addr }) list.map { if (it.address == addr) c else it } else list + c
        }
        _states.update { if (it.containsKey(addr)) it else it + (addr to LightState()) }
        save()
        publishDevices()
        listeners.forEach { it.onChanged(addr, null, state(addr)) }
    }

    @Synchronized
    fun remove(address: String) {
        val old = _states.value[address]
        _configs.update { list -> list.filterNot { it.address == address } }
        _states.update { it - address }
        protocols.remove(address)
        save()
        publishDevices()
        listeners.forEach { it.onChanged(address, old, null) }
    }

    // ---- control ---------------------------------------------------------

    fun setPower(address: String, on: Boolean, source: Source = Source.UI) =
        mutate(address, source) { it.copy(on = on) }

    fun toggle(address: String, source: Source = Source.UI) =
        mutate(address, source) { it.copy(on = !it.on) }

    /** Setting brightness on a light that's off turns it on, like Apple Home. */
    fun setBrightness(address: String, percent: Int, source: Source = Source.UI) =
        mutate(address, source) { it.copy(brightness = percent.coerceIn(1, 100), on = true) }

    fun setColor(address: String, hue: Float, saturation: Float, source: Source = Source.UI) =
        mutate(address, source) { it.copy(hue = hue, saturation = saturation.coerceIn(0f, 1f), on = true) }

    /** HomeKit sets hue and saturation as two separate writes; don't force power on. */
    fun setHue(address: String, hue: Float, source: Source) = mutate(address, source) { it.copy(hue = hue) }
    fun setSaturation(address: String, sat: Float, source: Source) =
        mutate(address, source) { it.copy(saturation = sat.coerceIn(0f, 1f)) }

    fun applyScene(scene: Scene) {
        _configs.value.forEach { cfg ->
            if (scene.state == null) setPower(cfg.address, false, Source.SCENE)
            else mutate(cfg.address, Source.SCENE) { scene.state }
        }
    }

    fun allOff(source: Source = Source.UI) = _configs.value.forEach { setPower(it.address, false, source) }

    /** HomeKit "identify": blink twice, then restore. */
    fun identify(address: String) {
        val cfg = config(address) ?: return
        scope.launch {
            val flash = LightState(on = true, brightness = 100, hue = 0f, saturation = 0f)
            val dark = LightState(on = false)
            repeat(2) {
                push(cfg, flash, dark, coalesce = false); delay(400)
                push(cfg, dark, flash, coalesce = false); delay(400)
            }
            push(cfg, state(address), dark, coalesce = false)
        }
    }

    /** Re-send the stored state to a light (e.g. after it reconnects / power cycles). */
    fun resync(address: String) {
        val cfg = config(address) ?: return
        push(cfg, state(address))
    }

    private fun mutate(address: String, source: Source, f: (LightState) -> LightState) {
        val cfg = config(address) ?: return
        var old: LightState? = null
        var new: LightState? = null
        _states.update { map ->
            val o = map[address] ?: LightState()
            val n = f(o)
            old = o; new = n
            if (n == o) map else map + (address to n)
        }
        val o = old ?: return
        val n = new ?: return
        if (o == n) return
        push(cfg, n, o)
        saveDebounced()
        Log.d(TAG, "$source ${cfg.name}: $o -> $n")
        listeners.forEach { it.onChanged(address, o, n) }
    }

    /**
     * Turn a state change into frames, following the same per-protocol rules
     * as the Python bridge:
     *  - power_off_disconnects (MrStar): "off" is brightness 0, never power_off
     *  - color_command_includes_brightness: fold brightness into RGB, otherwise
     *    send the full-value color plus a separate brightness command
     *  - brightness-only lights (Wi-Fi dimmer): just brightness
     */
    private fun push(cfg: LightConfig, s: LightState, old: LightState? = null, coalesce: Boolean = true) {
        val p = protoFor(cfg)
        val cmds = mutableListOf<Cmd>()
        if (!s.on) {
            val off = if (p.powerOffDisconnects) p.setBrightness(0) else p.powerOff()
            cmds += Cmd(off ?: p.powerOff(), power = true)
        } else {
            if (old == null || !old.on) cmds += Cmd(p.powerOn(), power = true)
            when {
                !p.supportsColor -> p.setBrightness(s.brightness)?.let { cmds += Cmd(it) }
                p.colorIncludesBrightness -> {
                    val (r, g, b) = s.rgb(); cmds += Cmd(p.setRgb(r, g, b))
                }
                else -> {
                    val (r, g, b) = s.displayRgb(); cmds += Cmd(p.setRgb(r, g, b))
                    p.setBrightness(s.brightness)?.let { cmds += Cmd(it) }
                }
            }
        }
        linksFor(cfg).send(cfg.address, cmds, coalesce)
    }

    // ---- persistence -----------------------------------------------------

    private var saveJob: kotlinx.coroutines.Job? = null
    private fun saveDebounced() {
        saveJob?.cancel()
        saveJob = scope.launch { delay(1500); save() }
    }

    @Synchronized
    private fun save() {
        val arr = JSONArray()
        for (c in _configs.value) {
            val s = state(c.address)
            arr.put(
                JSONObject()
                    .put("address", c.address).put("name", c.name).put("protocol", c.protocol)
                    .put("room", c.room).put("icon", c.icon).put("onDemand", c.onDemand)
                    .put("state", JSONObject().put("on", s.on).put("brightness", s.brightness)
                        .put("hue", s.hue.toDouble()).put("saturation", s.saturation.toDouble()))
            )
        }
        val text = JSONObject().put("lights", arr).toString(2)
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(text)
        tmp.renameTo(file)
        Backup.save(context, file.name, text)
    }

    private fun load() {
        Backup.restoreIfMissing(context, file.name, file)
        if (!file.exists()) return
        runCatching {
            val arr = JSONObject(file.readText()).getJSONArray("lights")
            val cfgs = mutableListOf<LightConfig>()
            val st = mutableMapOf<String, LightState>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val c = LightConfig(
                    o.getString("address"), o.getString("name"), o.getString("protocol"),
                    o.optString("room", "Home"), o.optString("icon", LightIcon.STRIP.id),
                    o.optBoolean("onDemand", false),
                )
                cfgs += c
                o.optJSONObject("state")?.let { s ->
                    st[c.address] = LightState(
                        s.optBoolean("on"), s.optInt("brightness", 100),
                        s.optDouble("hue", 32.0).toFloat(), s.optDouble("saturation", 0.55).toFloat(),
                    )
                }
            }
            _configs.value = cfgs
            _states.value = st
        }.onFailure { Log.e(TAG, "Failed to load lights.json", it) }
    }

    companion object { private const val TAG = "LightRepo" }
}
