package com.nexuslights.panel.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
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
import java.util.UUID

/** One frame to write. [power] frames survive coalescing (see [LightLinks.send]). */
class Cmd(val bytes: ByteArray, val power: Boolean = false)

/** Something that delivers command frames to lights: BLE or Wi-Fi. */
interface LightLinks {
    val links: StateFlow<Map<String, LinkStatus>>
    fun setKnownDevices(configs: List<LightConfig>)

    /**
     * Queue frames for a light. With [coalesce], this batch replaces any
     * batch not yet sent (keeping a pending power frame), so dragging a
     * slider sends only the latest value instead of a backlog.
     */
    fun send(address: String, cmds: List<Cmd>, coalesce: Boolean)
    fun retryAll()
}

internal fun mergeBatches(old: List<Cmd>?, new: List<Cmd>): List<Cmd> =
    if (old == null || new.any { it.power }) new else listOfNotNull(old.firstOrNull { it.power }) + new

/**
 * Talks GATT to every configured BLE strip. Port of nexuslights/ble_transport.py
 * (air-bridge), including its real-hardware fixes:
 *  - subscribe to the protocol's notify characteristic before writing
 *    (LEDnetWF and ELK-BLEDDM ignore writes otherwise)
 *  - acknowledged writes for protocols that need them (MrStar, ELK-BLEDDM)
 *  - a write that times out means a dead link: reconnect and retry once
 *  - on-demand lights connect per command and disconnect when idle
 *
 * All state lives on one HandlerThread; one GATT operation in flight per
 * device, and connection attempts are serialized because the MTK stack on
 * this tablet gets flaky with parallel connects.
 */
@SuppressLint("MissingPermission")
class BleController(private val ctx: Context) : LightLinks {

    private val thread = HandlerThread("ble").apply { start() }
    private val h = Handler(thread.looper)
    private val manager = ctx.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val adapter: BluetoothAdapter? get() = manager.adapter

    private val _links = MutableStateFlow<Map<String, LinkStatus>>(emptyMap())
    override val links: StateFlow<Map<String, LinkStatus>> = _links.asStateFlow()

    /** Treat every light as on-demand (for radios that can't hold many links). */
    @Volatile var forceOnDemand = false
        set(v) { field = v; h.post { devs.values.forEach { applyMode(it) } } }

    private inner class Dev(val address: String, var info: LightProtocol, var onDemandCfg: Boolean) {
        val onDemand get() = onDemandCfg || forceOnDemand
        var gatt: BluetoothGatt? = null
        var ch: BluetoothGattCharacteristic? = null
        var status = LinkStatus.DISCONNECTED
        val queue = ArrayDeque<Cmd>()
        var pending: List<Cmd>? = null
        var inFlight: Cmd? = null
        var retriedInFlight = false
        var failures = 0
        var removed = false
        val hasWork get() = queue.isNotEmpty() || pending != null || inFlight != null
        val writeTimeout = Runnable { onWriteTimeout(this) }
        val connectTimeout = Runnable {
            Log.w(TAG, "$address connect timeout")
            onLinkLost(this, wasConnected = false)
        }
        val reconnect = Runnable { ensureConnected(this) }
        val idleDisconnect = Runnable {
            if (!hasWork && status == LinkStatus.CONNECTED) {
                closeGatt(this); setStatus(this, LinkStatus.IDLE); nextConnect()
            }
        }
    }

    private val devs = LinkedHashMap<String, Dev>()
    private var connecting: Dev? = null
    private val waiting = ArrayDeque<Dev>()

    init {
        ctx.registerReceiver(object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                when (i.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1)) {
                    BluetoothAdapter.STATE_ON -> h.post { devs.values.forEach { it.failures = 0; applyMode(it) } }
                    BluetoothAdapter.STATE_TURNING_OFF, BluetoothAdapter.STATE_OFF -> h.post {
                        devs.values.forEach { closeGatt(it); setStatus(it, LinkStatus.DISCONNECTED) }
                        connecting = null; waiting.clear()
                    }
                }
            }
        }, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED))
        h.post { if (adapter?.isEnabled == false) runCatching { adapter?.enable() } }
    }

    override fun setKnownDevices(configs: List<LightConfig>) {
        val ble = configs.filter { LightProtocol.info(it.protocol)?.transport == LightProtocol.Transport.BLE }
        h.post {
            val wanted = ble.associateBy { it.address }
            (devs.keys - wanted.keys).toList().forEach { addr ->
                devs.remove(addr)?.let { d -> d.removed = true; cancelTimers(d); closeGatt(d) }
                waiting.removeAll { it.address == addr }
                if (connecting?.address == addr) { connecting = null; nextConnect() }
                _links.update { it - addr }
            }
            wanted.forEach { (addr, cfg) ->
                val info = LightProtocol.info(cfg.protocol) ?: return@forEach
                val d = devs.getOrPut(addr) { Dev(addr, info, cfg.onDemand) }
                if (d.info.id != info.id) { d.info = info; closeGatt(d); setStatus(d, LinkStatus.DISCONNECTED) }
                d.onDemandCfg = cfg.onDemand
                applyMode(d)
            }
        }
    }

    override fun send(address: String, cmds: List<Cmd>, coalesce: Boolean) {
        h.post {
            val d = devs[address] ?: return@post
            if (coalesce) d.pending = mergeBatches(d.pending, cmds) else d.queue.addAll(cmds)
            h.removeCallbacks(d.idleDisconnect)
            if (d.status == LinkStatus.FAILED) { d.failures = 0; h.removeCallbacks(d.reconnect) }
            ensureConnected(d)
            pump(d)
        }
    }

    override fun retryAll() {
        h.post { devs.values.forEach { it.failures = 0; h.removeCallbacks(it.reconnect); applyMode(it) } }
    }

    // ---- connection management (handler thread only) ----------------------

    /** Persistent lights stay connected; on-demand lights only connect with work queued. */
    private fun applyMode(d: Dev) {
        if (d.onDemand) {
            if (d.status == LinkStatus.DISCONNECTED) setStatus(d, LinkStatus.IDLE)
            if (d.hasWork) ensureConnected(d)
            else if (d.status == LinkStatus.CONNECTED) h.postDelayed(d.idleDisconnect, IDLE_MS)
        } else {
            if (d.status == LinkStatus.IDLE) setStatus(d, LinkStatus.DISCONNECTED)
            ensureConnected(d)
        }
    }

    private fun ensureConnected(d: Dev) {
        if (d.removed || d.status == LinkStatus.CONNECTED || d.status == LinkStatus.CONNECTING) return
        if (adapter?.isEnabled != true) return
        if (d.onDemand && !d.hasWork) return
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
            if (!d.removed && d.status != LinkStatus.CONNECTED && (!d.onDemand || d.hasWork)) startConnect(d)
        }
    }

    private fun onLinkLost(d: Dev, wasConnected: Boolean) {
        cancelTimers(d)
        closeGatt(d)
        d.inFlight?.let { d.queue.addFirst(it) }
        d.inFlight = null
        if (connecting === d) connecting = null
        if (!wasConnected) d.failures++
        val idle = d.onDemand && !d.hasWork
        setStatus(d, when {
            d.failures >= 3 -> LinkStatus.FAILED
            idle -> LinkStatus.IDLE
            else -> LinkStatus.DISCONNECTED
        })
        if (!d.removed && !idle) {
            // Back off 1s, 2s, 4s ... 60s and keep trying forever, so a strip
            // that was unplugged comes back on its own.
            val delayMs = (1000L shl d.failures.coerceAtMost(6)).coerceAtMost(60_000L)
            h.postDelayed(d.reconnect, if (wasConnected) 500 else delayMs)
        }
        if (d.onDemand && d.failures >= 3) {
            // Don't keep stale slider frames around forever for an unreachable light.
            d.queue.clear(); d.pending = null
        }
        nextConnect()
    }

    private fun cancelTimers(d: Dev) {
        h.removeCallbacks(d.writeTimeout); h.removeCallbacks(d.connectTimeout)
        h.removeCallbacks(d.reconnect); h.removeCallbacks(d.idleDisconnect)
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

    private fun ready(d: Dev) {
        d.failures = 0
        h.removeCallbacks(d.connectTimeout)
        if (connecting === d) connecting = null
        setStatus(d, LinkStatus.CONNECTED)
        Log.i(TAG, "${d.address} ready (${d.info.id})")
        pump(d)
        nextConnect()
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
                val p = d.info
                val ch = p.writeCharacteristic?.let { uuid -> gatt.services.firstNotNullOfOrNull { it.getCharacteristic(uuid) } }
                if (status != BluetoothGatt.GATT_SUCCESS || ch == null) {
                    Log.w(TAG, "${d.address}: ${p.writeCharacteristic} not found (status=$status)")
                    onLinkLost(d, false); return@post
                }
                ch.writeType = when {
                    p.writeWithResponse -> BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                    ch.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0 ->
                        BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                    else -> BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                }
                d.ch = ch

                val notify = p.notifyCharacteristic?.let { uuid -> gatt.services.firstNotNullOfOrNull { it.getCharacteristic(uuid) } }
                if (notify == null) { ready(d); return@post }
                gatt.setCharacteristicNotification(notify, true)
                val cccd = notify.getDescriptor(CCCD)
                @Suppress("DEPRECATION")
                if (cccd == null || !run {
                        cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                        gatt.writeDescriptor(cccd)
                    }) {
                    ready(d) // nothing to wait for
                }
            }
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            h.post { if (gatt === d.gatt && d.status == LinkStatus.CONNECTING) ready(d) }
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            h.post {
                if (gatt !== d.gatt) return@post
                h.removeCallbacks(d.writeTimeout)
                d.inFlight = null
                d.retriedInFlight = false
                // Cheap strip MCUs drop frames that arrive back-to-back.
                h.postDelayed({ pump(d) }, FRAME_GAP_MS)
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun pump(d: Dev) {
        if (d.status != LinkStatus.CONNECTED || d.inFlight != null) return
        val gatt = d.gatt ?: return
        val ch = d.ch ?: return
        val cmd = d.queue.removeFirstOrNull() ?: d.pending?.let { batch ->
            d.pending = batch.drop(1).ifEmpty { null }
            batch.firstOrNull()
        }
        if (cmd == null) {
            if (d.onDemand) { h.removeCallbacks(d.idleDisconnect); h.postDelayed(d.idleDisconnect, IDLE_MS) }
            return
        }
        ch.value = cmd.bytes
        if (gatt.writeCharacteristic(ch)) {
            d.inFlight = cmd
            h.postDelayed(d.writeTimeout, if (d.info.writeWithResponse) 2_000 else 400)
        } else {
            // Stack busy: put it back and try again shortly.
            d.queue.addFirst(cmd)
            h.postDelayed({ pump(d) }, 40)
        }
    }

    private fun onWriteTimeout(d: Dev) {
        if (!d.info.writeWithResponse) {
            // Write-without-response stacks don't always report completion; move on.
            d.inFlight = null; pump(d); return
        }
        // An acknowledged write that never got its ack: the link is dead even if
        // Android still says "connected". Reconnect and retry the frame once.
        Log.w(TAG, "${d.address}: write timed out on a stale link; reconnecting")
        val cmd = d.inFlight
        d.inFlight = null
        if (cmd != null && !d.retriedInFlight) { d.retriedInFlight = true; d.queue.addFirst(cmd) } else d.retriedInFlight = false
        onLinkLost(d, wasConnected = true)
    }

    companion object {
        private const val TAG = "Ble"
        private const val FRAME_GAP_MS = 20L
        private const val IDLE_MS = 3_000L
        private val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }
}
