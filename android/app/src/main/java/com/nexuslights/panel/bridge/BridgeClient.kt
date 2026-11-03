package com.nexuslights.panel.bridge

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

/**
 * HTTP client for the Mac bridge's wall-panel API (nexuslights/panel_api.py).
 * The bridge owns the lights and Apple Home; this only reads state and sends
 * commands.
 *
 *  - polls GET /api/lights every 2 s while the screen is on (and not at all
 *    while it's off: nothing to show, nothing to spend battery on)
 *  - per light, at most one command in flight; newer changes merge into the
 *    pending one, so dragging a slider sends only the latest value
 *  - if the bridge stops answering at its configured address (the Air got a
 *    new DHCP lease), finds it again via its HomeKit mDNS advert
 */
class BridgeClient(
    /** Only used to find a moved bridge over mDNS; null in JVM tests. */
    private val ctx: Context?,
    private val url: () -> String,
    private val token: () -> String,
    private val onUrlDiscovered: (String) -> Unit,
) {
    enum class Health { NOT_CONFIGURED, CONNECTING, CONNECTED, UNREACHABLE, UNAUTHORIZED, API_DISABLED }

    data class Status(val health: Health = Health.CONNECTING, val detail: String = "", val lastOkAt: Long = 0)

    data class RemoteLight(
        val id: String, val name: String, val protocol: String, val color: Boolean,
        val connected: Boolean, val onDemand: Boolean,
        val on: Boolean, val brightness: Int, val hue: Float?, val saturation: Float?,
    )

    data class RemoteSwitch(val id: String, val name: String, val on: Boolean)

    data class Snapshot(
        val lights: List<RemoteLight> = emptyList(),
        val switches: List<RemoteSwitch> = emptyList(),
        val panelSensors: Boolean = false,
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _status = MutableStateFlow(Status())
    val status: StateFlow<Status> = _status.asStateFlow()
    private val _snapshot = MutableStateFlow(Snapshot())
    val snapshot: StateFlow<Snapshot> = _snapshot.asStateFlow()

    /** Fires after every successful poll or command response (used to clear optimistic UI state). */
    @Volatile var onFresh: (() -> Unit)? = null

    /** When the last command response updated the snapshot; older polls are stale. */
    @Volatile private var lastCommandAt = 0L

    private var pollJob: Job? = null
    @Volatile private var polling = false
    private var failures = 0
    private var discovering = false

    // ---- polling -------------------------------------------------------------

    /** Poll while [active]; a call with true also refreshes right away. */
    fun setActive(active: Boolean) {
        polling = active
        if (active && pollJob?.isActive != true) {
            pollJob = scope.launch {
                while (isActive && polling) {
                    refresh()
                    delay(if (_status.value.health == Health.CONNECTED) POLL_MS else RETRY_MS)
                }
            }
        }
    }

    fun refreshNow() { scope.launch { refresh() } }

    private suspend fun refresh() {
        if (url().isBlank() || token().isBlank()) {
            _status.value = Status(Health.NOT_CONFIGURED, "Set the bridge address and token in Settings")
            return
        }
        try {
            val startedAt = System.nanoTime()
            val body = request("GET", "/api/lights", null)
            // A command answered while this poll was in flight is newer than it.
            if (lastCommandAt < startedAt) _snapshot.value = parseSnapshot(JSONObject(body))
            failures = 0
            if (_status.value.health != Health.CONNECTED) {
                Log.i(TAG, "connected to bridge ${url()}: ${_snapshot.value.lights.size} light(s)")
            }
            _status.value = Status(Health.CONNECTED, url(), System.currentTimeMillis())
            onFresh?.invoke()
        } catch (e: HttpError) {
            _status.value = when (e.code) {
                401 -> Status(Health.UNAUTHORIZED, "The bridge rejected the token", _status.value.lastOkAt)
                403 -> Status(Health.API_DISABLED, "Panel API is off on the bridge", _status.value.lastOkAt)
                404 -> Status(Health.API_DISABLED, "Bridge is too old: update nexuslights on the Mac", _status.value.lastOkAt)
                else -> Status(Health.UNREACHABLE, "Bridge error ${e.code}", _status.value.lastOkAt)
            }
        } catch (e: Exception) {
            failures++
            if (failures == 1) Log.w(TAG, "can't reach bridge ${url()}: ${e.message}")
            _status.value = Status(Health.UNREACHABLE, "Can't reach ${hostOf(url())}", _status.value.lastOkAt)
            if (failures >= 3) discover()
        }
    }

    // ---- commands ------------------------------------------------------------

    private val pending = ConcurrentHashMap<String, JSONObject>()
    private val senders = ConcurrentHashMap<String, Mutex>()

    /**
     * Send a light change ("on", "brightness", "hue", "saturation" in bridge
     * units). Merges into any not-yet-sent change for the same light.
     */
    fun sendLight(id: String, changes: Map<String, Any>) {
        pending.compute(id) { _, old ->
            (old ?: JSONObject()).also { o -> changes.forEach { (k, v) -> o.put(k, v) } }
        }
        val mutex = senders.getOrPut(id) { Mutex() }
        scope.launch {
            mutex.withLock {
                val body = pending.remove(id) ?: return@withLock
                try {
                    val resp = JSONObject(request("POST", "/api/lights/$id", body.toString()))
                    val updated = parseLight(resp)
                    val snap = _snapshot.value
                    if (snap.lights.any { it.id == id }) {
                        lastCommandAt = System.nanoTime()
                        _snapshot.value = snap.copy(lights = snap.lights.map { if (it.id == id) updated else it })
                        onFresh?.invoke()
                    } else {
                        refresh() // haven't polled yet: get the full list
                    }
                } catch (e: HttpError) {
                    Log.w(TAG, "light $id: HTTP ${e.code} ${e.body}")
                    // 502 = the bridge accepted it but the light didn't answer; the poll shows the truth.
                } catch (e: Exception) {
                    Log.w(TAG, "light $id: ${e.message}")
                    refresh()
                }
            }
        }
    }

    fun sendSwitch(id: String, on: Boolean) {
        scope.launch {
            try {
                val resp = JSONObject(request("POST", "/api/switches/$id", JSONObject().put("on", on).toString()))
                lastCommandAt = System.nanoTime()
                _snapshot.value = _snapshot.value.let { s ->
                    s.copy(switches = s.switches.map { if (it.id == id) it.copy(on = resp.optBoolean("on", on)) else it })
                }
            } catch (e: Exception) {
                Log.w(TAG, "switch $id: ${e.message}")
            }
        }
    }

    /** Feed the bridge's optional "Wall Panel" motion / light sensors. */
    fun reportPanel(motion: Boolean? = null, lux: Float? = null) {
        if (!_snapshot.value.panelSensors || _status.value.health != Health.CONNECTED) return
        val body = JSONObject()
        motion?.let { body.put("motion", it) }
        lux?.let { body.put("lux", it.toDouble()) }
        scope.launch { runCatching { request("POST", "/api/panel", body.toString()) } }
    }

    /** One-off check for the Settings screen. */
    suspend fun test(): String = try {
        val s = parseSnapshot(JSONObject(request("GET", "/api/lights", null)))
        "Connected: ${s.lights.size} light(s)"
    } catch (e: HttpError) {
        if (e.code == 401) "Wrong token" else "Bridge answered HTTP ${e.code}"
    } catch (e: Exception) {
        "Can't reach the bridge: ${e.message}"
    }

    // ---- HTTP ------------------------------------------------------------------

    private class HttpError(val code: Int, val body: String) : IOException("HTTP $code")

    private fun request(method: String, path: String, body: String?): String {
        val conn = URL(url().trimEnd('/') + path).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = method
            conn.connectTimeout = 4_000
            // A command waits for the bridge's Bluetooth write, which can take a while.
            conn.readTimeout = if (method == "GET") 6_000 else 35_000
            conn.setRequestProperty("Authorization", "Bearer ${token()}")
            conn.setRequestProperty("Accept", "application/json")
            if (body != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.outputStream.use { it.write(body.toByteArray()) }
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
            if (code !in 200..299) throw HttpError(code, text)
            return text
        } finally {
            conn.disconnect()
        }
    }

    private fun parseSnapshot(o: JSONObject): Snapshot {
        val lights = o.optJSONArray("lights") ?: JSONArray()
        val switches = o.optJSONArray("switches") ?: JSONArray()
        return Snapshot(
            lights = (0 until lights.length()).map { parseLight(lights.getJSONObject(it)) },
            switches = (0 until switches.length()).map {
                val s = switches.getJSONObject(it)
                RemoteSwitch(s.get("id").toString(), s.optString("name"), s.optBoolean("on"))
            },
            panelSensors = o.optBoolean("panel_sensors"),
        )
    }

    private fun parseLight(o: JSONObject) = RemoteLight(
        id = o.get("id").toString(),
        name = o.optString("name"),
        protocol = o.optString("protocol"),
        color = o.optBoolean("color", true),
        connected = o.optBoolean("connected"),
        onDemand = o.optBoolean("on_demand"),
        on = o.optBoolean("on"),
        brightness = o.optInt("brightness", 100),
        hue = if (o.isNull("hue")) null else o.optDouble("hue").toFloat(),
        saturation = if (o.isNull("saturation")) null else o.optDouble("saturation").toFloat(),
    )

    // ---- finding the bridge again ----------------------------------------------

    /**
     * The bridge advertises itself for HomeKit as "_hap._tcp" named
     * "NexusLights...". If its IP changed, resolve that advert and keep the
     * configured port. Android 8 can't resolve ".local" hostnames, so this
     * is how a moved bridge gets found.
     */
    private fun discover() {
        if (discovering) return
        val nsd = ctx?.getSystemService(NsdManager::class.java) ?: return
        discovering = true
        val listener = object : NsdManager.DiscoveryListener {
            override fun onServiceFound(info: NsdServiceInfo) {
                if (!info.serviceName.startsWith("NexusLights")) return
                @Suppress("DEPRECATION")
                nsd.resolveService(info, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(si: NsdServiceInfo, errorCode: Int) {}
                    override fun onServiceResolved(si: NsdServiceInfo) {
                        val host = si.host as? Inet4Address ?: return
                        val candidate = replaceHost(url(), host.hostAddress ?: return)
                        if (candidate != url()) {
                            Log.i(TAG, "bridge moved? trying $candidate")
                            onUrlDiscovered(candidate)
                            refreshNow()
                        }
                    }
                })
            }
            override fun onServiceLost(info: NsdServiceInfo) {}
            override fun onDiscoveryStarted(serviceType: String) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) { discovering = false }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
        }
        runCatching { nsd.discoverServices("_hap._tcp.", NsdManager.PROTOCOL_DNS_SD, listener) }
            .onFailure { discovering = false; return }
        scope.launch {
            delay(15_000)
            runCatching { nsd.stopServiceDiscovery(listener) }
            discovering = false
        }
    }

    companion object {
        private const val TAG = "Bridge"
        private const val POLL_MS = 2_000L
        private const val RETRY_MS = 5_000L

        fun hostOf(url: String): String = runCatching { URL(url).host }.getOrDefault(url)

        fun replaceHost(url: String, host: String): String = runCatching {
            val u = URL(url)
            URL(u.protocol, host, if (u.port == -1) 8080 else u.port, u.path).toString()
        }.getOrDefault(url)

        /** Accept "192.168.70.104", "192.168.70.104:8080" or a full URL. */
        fun normalizeUrl(input: String): String {
            val t = input.trim().trimEnd('/')
            if (t.isEmpty()) return ""
            val withScheme = if ("://" in t) t else "http://$t"
            return runCatching {
                val u = URL(withScheme)
                URL(u.protocol, u.host, if (u.port == -1) 8080 else u.port, "").toString()
            }.getOrDefault(withScheme)
        }
    }
}
