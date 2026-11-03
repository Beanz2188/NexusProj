package com.nexuslights.panel.wifi

import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import com.nexuslights.panel.ble.Cmd
import com.nexuslights.panel.ble.LightLinks
import com.nexuslights.panel.ble.mergeBatches
import com.nexuslights.panel.data.LightConfig
import com.nexuslights.panel.data.LinkStatus
import com.nexuslights.panel.protocol.FluxLed
import com.nexuslights.panel.protocol.LightProtocol
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Magic Home / flux_led Wi-Fi controllers over TCP 5577 (port of
 * nexuslights/wifi_light.py). The socket is held open and re-opened on
 * failure; a state query on connect doubles as a reachability check.
 * Blocking I/O runs on its own thread so a slow network can't stall BLE.
 */
class WifiLightController : LightLinks {
    private val thread = HandlerThread("wifi-lights").apply { start() }
    private val h = Handler(thread.looper)

    private val _links = MutableStateFlow<Map<String, LinkStatus>>(emptyMap())
    override val links: StateFlow<Map<String, LinkStatus>> = _links.asStateFlow()

    private class Dev(val ip: String) {
        var socket: Socket? = null
        val queue = ArrayDeque<Cmd>()
        var pending: List<Cmd>? = null
        var failures = 0
    }

    private val devs = LinkedHashMap<String, Dev>()
    private val query = FluxLed().stateQuery()

    private val healthCheck = object : Runnable {
        override fun run() {
            devs.values.forEach { d -> if (d.socket == null) runCatching { connect(d) } }
            h.postDelayed(this, 60_000)
        }
    }

    init { h.postDelayed(healthCheck, 2_000) }

    override fun setKnownDevices(configs: List<LightConfig>) {
        val wifi = configs.filter { LightProtocol.info(it.protocol)?.transport == LightProtocol.Transport.WIFI }
        h.post {
            val wanted = wifi.map { it.address }.toSet()
            (devs.keys - wanted).toList().forEach { ip -> devs.remove(ip)?.let { close(it) }; _links.update { it - ip } }
            wanted.forEach { ip -> if (ip !in devs) { devs[ip] = Dev(ip); setStatus(ip, LinkStatus.DISCONNECTED) } }
        }
    }

    override fun send(address: String, cmds: List<Cmd>, coalesce: Boolean) {
        h.post {
            val d = devs[address] ?: return@post
            if (coalesce) d.pending = mergeBatches(d.pending, cmds) else d.queue.addAll(cmds)
            pump(d)
        }
    }

    override fun retryAll() { h.post { devs.values.forEach { it.failures = 0; runCatching { connect(it) } } } }

    private fun pump(d: Dev) {
        while (true) {
            val cmd = d.queue.removeFirstOrNull() ?: d.pending?.let { batch ->
                d.pending = batch.drop(1).ifEmpty { null }
                batch.firstOrNull()
            } ?: return
            if (!write(d, cmd.bytes)) {
                // drop the rest of a coalesced batch for an unreachable light
                d.pending = null
                return
            }
        }
    }

    /** Write with one reconnect-and-retry, like WifiLight's _ensure(). */
    private fun write(d: Dev, bytes: ByteArray): Boolean {
        repeat(2) { attempt ->
            try {
                val s = d.socket ?: connect(d)
                s.getOutputStream().apply { write(bytes); flush() }
                drain(s)
                d.failures = 0
                setStatus(d.ip, LinkStatus.CONNECTED)
                return true
            } catch (e: IOException) {
                Log.w(TAG, "${d.ip}: write failed (attempt ${attempt + 1}): ${e.message}")
                close(d)
            }
        }
        d.failures++
        setStatus(d.ip, if (d.failures >= 2) LinkStatus.FAILED else LinkStatus.DISCONNECTED)
        return false
    }

    private fun connect(d: Dev): Socket {
        close(d)
        val s = Socket()
        try {
            s.connect(InetSocketAddress(d.ip, FluxLed.PORT), 3_000)
            s.soTimeout = 2_000
            s.tcpNoDelay = true
            s.getOutputStream().apply { write(query); flush() }
            // 14-byte state reply; content isn't needed, only that it answers.
            val buf = ByteArray(14)
            var n = 0
            while (n < buf.size) {
                val r = s.getInputStream().read(buf, n, buf.size - n)
                if (r < 0) throw IOException("closed during state query")
                n += r
            }
        } catch (e: IOException) {
            runCatching { s.close() }
            setStatus(d.ip, LinkStatus.FAILED)
            throw e
        }
        d.socket = s
        setStatus(d.ip, LinkStatus.CONNECTED)
        return s
    }

    /** Discard any replies so the controller's TCP window never fills up. */
    private fun drain(s: Socket) {
        val inp = s.getInputStream()
        val skip = ByteArray(256)
        while (inp.available() > 0) if (inp.read(skip) < 0) throw IOException("closed")
    }

    private fun close(d: Dev) {
        d.socket?.let { runCatching { it.close() } }
        d.socket = null
    }

    private fun setStatus(ip: String, s: LinkStatus) = _links.update { it + (ip to s) }

    companion object { private const val TAG = "WifiLight" }
}
