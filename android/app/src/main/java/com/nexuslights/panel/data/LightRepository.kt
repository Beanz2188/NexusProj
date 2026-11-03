package com.nexuslights.panel.data

import android.os.SystemClock
import com.nexuslights.panel.bridge.BridgeClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlin.math.roundToInt

/**
 * What the UI sees: the bridge's lights, with
 *  - optimistic state, so a tap shows instantly instead of after the next
 *    poll (it's dropped once the bridge reports the same thing, or after a
 *    few seconds if it never does)
 *  - the panel's own room/icon choices merged in
 */
class LightRepository(
    private val client: BridgeClient,
    private val settings: PanelSettings,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private data class Override(val state: LightState, val at: Long)
    private val overrides = MutableStateFlow<Map<String, Override>>(emptyMap())

    init {
        client.onFresh = { pruneOverrides() }
    }

    val lights: StateFlow<List<Light>> = combine(
        client.snapshot, client.status, overrides, settings.customizations,
    ) { snap, status, ov, custom ->
        val reachable = status.health == BridgeClient.Health.CONNECTED
        snap.lights.map { r ->
            val c = custom[r.id]
            val cfg = LightConfig(
                id = r.id, name = r.name, protocol = r.protocol, supportsColor = r.color,
                room = c?.room ?: "Home", icon = c?.icon ?: LightIcon.guess(r.name).id,
            )
            val remote = r.toState()
            val link = when {
                !reachable -> LinkStatus.UNKNOWN
                r.connected -> LinkStatus.CONNECTED
                r.onDemand -> LinkStatus.IDLE
                else -> LinkStatus.FAILED
            }
            Light(cfg, ov[r.id]?.state ?: remote, link)
        }
    }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    val modes: StateFlow<List<ModeSwitch>> = client.snapshot
        .map { s -> s.switches.map { ModeSwitch(it.id, it.name, it.on) } }
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    private fun BridgeClient.RemoteLight.toState() = LightState(
        on = on,
        brightness = brightness,
        hue = hue ?: 40f,
        saturation = (saturation ?: 10f) / 100f,
    )

    fun state(id: String) = lights.value.firstOrNull { it.config.id == id }?.state ?: LightState()

    // ---- control -----------------------------------------------------------

    fun toggle(id: String) = setPower(id, !state(id).on)

    fun setPower(id: String, on: Boolean) {
        optimistic(id) { it.copy(on = on) }
        client.sendLight(id, mapOf("on" to on))
    }

    /** Like Apple Home: moving brightness on a light that's off turns it on. */
    fun setBrightness(id: String, percent: Int) {
        val p = percent.coerceIn(1, 100)
        optimistic(id) { it.copy(on = true, brightness = p) }
        client.sendLight(id, mapOf("on" to true, "brightness" to p))
    }

    fun setColor(id: String, hue: Float, saturation: Float) {
        val s = saturation.coerceIn(0f, 1f)
        optimistic(id) { it.copy(on = true, hue = hue, saturation = s) }
        client.sendLight(id, mapOf("on" to true, "hue" to hue.toDouble(), "saturation" to (s * 100.0)))
    }

    fun applyScene(scene: Scene) {
        lights.value.forEach { l ->
            val id = l.config.id
            val target = scene.state
            if (target == null) {
                setPower(id, false)
            } else {
                optimistic(id) { target }
                val cmd = mutableMapOf<String, Any>("on" to true, "brightness" to target.brightness)
                if (l.config.supportsColor) {
                    cmd["hue"] = target.hue.toDouble()
                    cmd["saturation"] = (target.saturation * 100.0)
                }
                client.sendLight(id, cmd)
            }
        }
    }

    fun allOff() = lights.value.filter { it.state.on }.forEach { setPower(it.config.id, false) }

    fun setMode(id: String, on: Boolean) = client.sendSwitch(id, on)

    // ---- optimistic state ----------------------------------------------------

    private fun optimistic(id: String, f: (LightState) -> LightState) {
        val next = f(state(id))
        overrides.update { it + (id to Override(next, SystemClock.elapsedRealtime())) }
    }

    /** Drop overrides the bridge has caught up with, or that are too old to trust. */
    private fun pruneOverrides() {
        val now = SystemClock.elapsedRealtime()
        val remote = client.snapshot.value.lights.associateBy { it.id }
        overrides.update { ov ->
            ov.filter { (id, o) ->
                val r = remote[id]?.toState() ?: return@filter false
                val matches = r.on == o.state.on && (!o.state.on || (
                    r.brightness == o.state.brightness &&
                        (remote[id]?.color != true ||
                            ((r.hue - o.state.hue).roundToInt() == 0 &&
                                ((r.saturation - o.state.saturation) * 100).roundToInt() == 0))))
                !matches && now - o.at < OVERRIDE_MS
            }
        }
    }

    companion object { private const val OVERRIDE_MS = 6_000L }
}
