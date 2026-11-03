package com.nexuslights.panel.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import com.nexuslights.panel.data.LightConfig
import com.nexuslights.panel.data.LinkStatus
import com.nexuslights.panel.protocol.LightProtocol
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Keeps a GATT connection open to every configured strip and writes command
 * frames to it. All state lives on one HandlerThread, which is what Android's
 * GATT stack wants anyway (one operation in flight per device, and the MTK
 * stack on this tablet gets flaky with parallel connection attempts, so
 * connects are serialized too).
 *
 * The rough Python equivalent is nexuslights/ble_transport.py.
 */
@SuppressLint("MissingPermission")
class BleController(private val ctx: Context) {

    private val thread = HandlerThread("ble").apply { start() }
    private val h = Handler(thread.looper)
    private val manager = ctx.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val adapter: BluetoothAdapter? get() = manager.adapter

    private val _links = MutableStateFlow<Map<String, LinkStatus>>(emptyMap())
    val links: StateFlow<Map<String, LinkStatus>> = _links.asStateFlow()

    private inner class Dev(val address: String, var protocol: LightProtocol) {
        var gatt: BluetoothGatt? = null
        var ch: BluetoothGattCharacteristic? = null
        var status = LinkStatus.DISCONNECTED
        val queue = ArrayDeque<ByteArray>()
        /** Latest "state" batch; newer batches replace older ones (slider drags). */
        var pending: List<ByteArray>? = null
        var writing = false
        var failures = 0
        var removed = false
        val writeTimeout = Runnable { writing = false; pump(this) }
        val connectTimeout = Runnable {
            Log.w(TAG, "$address connect timeout")
            gatt?.disconnect(); onLinkLost(this, wasConnected = false)
        }
        val reconnect = Runnable { ensureConnected(this) }
    }

    private val devs = LinkedHashMap<String, Dev>()
    private var connecting: Dev? = null
    private val waiting = ArrayDeque<Dev>()

    init {
        ctx.registerReceiver(object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                when (i.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1)) {
                    BluetoothAdapter.STATE_ON -> h.post { devs.values.forEach { it.failures = 0; ensureConnected(it) } }
                    BluetoothAdapter.STATE_TURNING_OFF, BluetoothAdapter.STATE_OFF -> h.post {
                        devs.values.forEach { closeGatt(it); setStatus(it, LinkStatus.DISCONNECTED) }
                        connecting = null; waiting.clear()
                    }
                }
            }
        }, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED))
        h.post { if (adapter?.isEnabled == false) runCatching { adapter?.enable() } }
    }

    fun setKnownDevices(configs: List<LightConfig>) = h.post {
        val wanted = configs.associateBy { it.address }
        (devs.keys - wanted.keys).toList().forEach { addr ->
            devs.remove(addr)?.let { d -> d.removed = true; h.removeCallbacksAndMessagesFor(d); closeGatt(d) }
            waiting.removeAll { it.address == addr }
            if (connecting?.address == addr) { connecting = null; nextConnect() }
            _links.update { it - addr }
        }
        wanted.forEach { (addr, cfg) ->
            val p = runCatching { cfg.protocolImpl }.getOrDefault(LightProtocol.Triones)
            val d = devs.getOrPut(addr) { Dev(addr, p) }
            if (d.protocol != p) { d.protocol = p; closeGatt(d); setStatus(d, LinkStatus.DISCONNECTED) }
            ensureConnected(d)
        }
    }

    /**
     * Queue frames for a light. With [coalesce], this batch replaces any batch
     * not yet sent (keeping a pending power frame), so a fast slider drag
     * sends only the latest color instead of a backlog.
     */
    fun send(address: String, frames: List<ByteArray>, coalesce: Boolean) = h.post {
        val d = devs[address] ?: return@post
        if (coalesce) {
            val old = d.pending
            val isPower = { f: ByteArray -> f.contentEquals(d.protocol.powerOn()) || f.contentEquals(d.protocol.powerOff()) }
            d.pending = if (old == null || frames.any(isPower)) frames
            else listOfNotNull(old.firstOrNull(isPower)) + frames
        } else {
            d.queue.addAll(frames)
        }
        if (d.status == LinkStatus.FAILED) { d.failures = 0; h.removeCallbacks(d.reconnect) }
        ensureConnected(d)
        pump(d)
    }

    /** Reconnect everything now (e.g. after the user taps a "not responding" tile). */
    fun retryAll() = h.post { devs.values.forEach { it.failures = 0; h.removeCallbacks(it.reconnect); ensureConnected(it) } }

    // ---- connection management (handler thread only) ----------------------

    private fun ensureConnected(d: Dev) {
        if (d.removed || d.status == LinkStatus.CONNECTED || d.status == LinkStatus.CONNECTING) return
        if (adapter?.isEnabled != true) return
        if (connecting != null && connecting !== d) {
            if (d !in waiting) waiting.addLast(d)
            return
        }
        startConnect(d)
    }

    private fun startConnect(d: Dev) {
        val device: BluetoothDevice = try {
            adapter!!.getRemoteDevice(d.address)
        } catch (e: IllegalArgumentException) {
            Log.e(TAG, "Bad address ${d.address}"); setStatus(d, LinkStatus.FAILED); return
        }
        connecting = d
        setStatus(d, LinkStatus.CONNECTING)
        closeGatt(d)
        d.gatt = device.connectGatt(ctx, false, callbackFor(d), BluetoothDevice.TRANSPORT_LE)
        h.postDelayed(d.connectTimeout, 15_000)
    }

    private fun nextConnect() {
        while (connecting == null && waiting.isNotEmpty()) {
            val d = waiting.removeFirst()
            if (!d.removed && d.status != LinkStatus.CONNECTED) startConnect(d)
        }
    }

    private fun onLinkLost(d: Dev, wasConnected: Boolean) {
        h.removeCallbacks(d.connectTimeout)
        h.removeCallbacks(d.writeTimeout)
        closeGatt(d)
        d.writing = false
        if (connecting === d) connecting = null
        if (!wasConnected) d.failures++
        setStatus(d, if (d.failures >= 3) LinkStatus.FAILED else LinkStatus.DISCONNECTED)
        if (!d.removed) {
            // Back off: 1s, 2s, 4s ... capped at 60s. Keeps retrying forever so
            // a strip that was unplugged comes back on its own.
            val delayMs = (1000L shl d.failures.coerceAtMost(6)).coerceAtMost(60_000L)
            h.postDelayed(d.reconnect, if (wasConnected) 500 else delayMs)
        }
        nextConnect()
    }

    private fun closeGatt(d: Dev) {
        d.gatt?.let { runCatching { it.disconnect() }; runCatching { it.close() } }
        d.gatt = null
        d.ch = null
    }

    private fun setStatus(d: Dev, s: LinkStatus) {
        d.status = s
        _links.update { it + (d.address to s) }
    }

    private fun callbackFor(d: Dev) = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            h.post {
                if (gatt !== d.gatt) { runCatching { gatt.close() }; return@post }
                if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                    // MTK stacks are happier with a short pause before discovery.
                    h.postDelayed({ if (gatt === d.gatt && !gatt.discoverServices()) onLinkLost(d, false) }, 300)
                } else {
                    Log.i(TAG, "${d.address} link lost status=$status state=$newState")
                    onLinkLost(d, wasConnected = d.status == LinkStatus.CONNECTED)
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            h.post {
                if (gatt !== d.gatt) return@post
                val p = d.protocol
                val ch = gatt.getService(p.service)?.getCharacteristic(p.writeCharacteristic)
                    ?: gatt.services.firstNotNullOfOrNull { it.getCharacteristic(p.writeCharacteristic) }
                if (status != BluetoothGatt.GATT_SUCCESS || ch == null) {
                    Log.w(TAG, "${d.address}: ${p.writeCharacteristic} not found (status=$status)")
                    gatt.disconnect(); onLinkLost(d, false); return@post
                }
                ch.writeType = if (ch.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0)
                    BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE else BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                d.ch = ch
                d.failures = 0
                h.removeCallbacks(d.connectTimeout)
                if (connecting === d) connecting = null
                setStatus(d, LinkStatus.CONNECTED)
                Log.i(TAG, "${d.address} ready")
                pump(d)
                nextConnect()
            }
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            h.post {
                if (gatt !== d.gatt) return@post
                h.removeCallbacks(d.writeTimeout)
                d.writing = false
                // Cheap strip MCUs drop frames that arrive back-to-back.
                h.postDelayed({ pump(d) }, FRAME_GAP_MS)
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun pump(d: Dev) {
        if (d.status != LinkStatus.CONNECTED || d.writing) return
        val gatt = d.gatt ?: return
        val ch = d.ch ?: return
        val frame = d.queue.removeFirstOrNull() ?: d.pending?.let { batch ->
            d.pending = batch.drop(1).ifEmpty { null }
            batch.firstOrNull()
        } ?: return
        ch.value = frame
        if (gatt.writeCharacteristic(ch)) {
            d.writing = true
            h.postDelayed(d.writeTimeout, 400)
        } else {
            // Stack busy: put it back and try again shortly.
            d.queue.addFirst(frame)
            h.postDelayed({ pump(d) }, 40)
        }
    }

    private fun Handler.removeCallbacksAndMessagesFor(d: Dev) {
        removeCallbacks(d.writeTimeout); removeCallbacks(d.connectTimeout); removeCallbacks(d.reconnect)
    }

    companion object {
        private const val TAG = "Ble"
        private const val FRAME_GAP_MS = 20L
    }
}
