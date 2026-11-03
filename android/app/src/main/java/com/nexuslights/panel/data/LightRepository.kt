package com.nexuslights.panel.data

import android.content.Context
import android.util.Log
import com.nexuslights.panel.ble.BleController
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
class LightRepository(context: Context, private val ble: BleController) {

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

    val lights: StateFlow<List<Light>> = combine(_configs, _states, ble.links) { cfgs, st, links ->
        cfgs.map { Light(it, st[it.address] ?: LightState(), links[it.address] ?: LinkStatus.DISCONNECTED) }
    }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    init {
        load()
        ble.setKnownDevices(_configs.value)
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
        ble.setKnownDevices(_configs.value)
        listeners.forEach { it.onChanged(addr, null, state(addr)) }
    }

    @Synchronized
    fun remove(address: String) {
        val old = _states.value[address]
        _configs.update { list -> list.filterNot { it.address == address } }
        _states.update { it - address }
        save()
        ble.setKnownDevices(_configs.value)
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
            val p = cfg.protocolImpl
            repeat(2) {
                ble.send(address, listOf(p.powerOn(), p.setRgb(255, 255, 255)), coalesce = false)
                delay(350)
                ble.send(address, listOf(p.powerOff()), coalesce = false)
                delay(350)
            }
            push(cfg, state(address))
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

    private fun push(cfg: LightConfig, s: LightState, old: LightState? = null) {
        val p = cfg.protocolImpl
        val frames = mutableListOf<ByteArray>()
        if (!s.on) {
            frames += p.powerOff()
        } else {
            if (old == null || !old.on) frames += p.powerOn()
            val (r, g, b) = s.rgb()
            frames += p.setRgb(r, g, b)
        }
        ble.send(cfg.address, frames, coalesce = true)
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
                    .put("room", c.room).put("icon", c.icon)
                    .put("state", JSONObject().put("on", s.on).put("brightness", s.brightness)
                        .put("hue", s.hue.toDouble()).put("saturation", s.saturation.toDouble()))
            )
        }
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(JSONObject().put("lights", arr).toString(2))
        tmp.renameTo(file)
    }

    private fun load() {
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
