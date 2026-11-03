package com.nexuslights.panel.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import com.nexuslights.panel.protocol.LightProtocol
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Finds nearby strips for the "Add Light" screen. Equivalent of
 * `nexus-lights scan`: the protocol is guessed from the advertised name or
 * service UUIDs.
 *
 * Android 8.1 only returns BLE scan results while Location is on. To avoid
 * leaving location running (battery), it's switched on just for the scan and
 * restored afterwards (needs WRITE_SECURE_SETTINGS, granted by provision.sh).
 */
@SuppressLint("MissingPermission")
class BleScanner(private val ctx: Context) {

    data class Found(
        val address: String,
        val name: String?,
        val rssi: Int,
        val guess: LightProtocol?,
    )

    private val main = Handler(Looper.getMainLooper())
    private val _results = MutableStateFlow<List<Found>>(emptyList())
    val results: StateFlow<List<Found>> = _results.asStateFlow()
    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private var restoreLocationMode: Int? = null
    private val stopRunnable = Runnable { stop() }

    private val callback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val name = result.scanRecord?.deviceName ?: result.device.name
            val uuids = result.scanRecord?.serviceUuids?.map { it.uuid }.orEmpty()
            val f = Found(
                result.device.address,
                name,
                result.rssi,
                LightProtocol.guess(name) ?: LightProtocol.byService(uuids),
            )
            val list = _results.value.filterNot { it.address == f.address } + f
            // likely strips first, then strongest signal
            _results.value = list.sortedWith(compareBy<Found>({ it.guess == null }, { -it.rssi }))
        }

        override fun onScanFailed(errorCode: Int) {
            Log.w(TAG, "scan failed $errorCode")
            _error.value = "Bluetooth scan failed (code $errorCode). Try again."
            stop()
        }
    }

    fun start(durationMs: Long = 12_000) {
        if (_scanning.value) return
        _error.value = null
        val adapter = ctx.getSystemService(BluetoothManager::class.java).adapter
        if (adapter == null || !adapter.isEnabled) {
            _error.value = "Bluetooth is off"; runCatching { adapter?.enable() }; return
        }
        if (ctx.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            _error.value = "Location permission is needed to scan (Android 8 requirement)"; return
        }
        ensureLocationOn()
        _results.value = emptyList()
        val scanner = adapter.bluetoothLeScanner ?: run { _error.value = "BLE scanner unavailable"; return }
        scanner.startScan(null, ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), callback)
        _scanning.value = true
        main.postDelayed(stopRunnable, durationMs)
    }

    fun stop() {
        main.removeCallbacks(stopRunnable)
        if (_scanning.value) {
            runCatching { ctx.getSystemService(BluetoothManager::class.java).adapter?.bluetoothLeScanner?.stopScan(callback) }
            _scanning.value = false
        }
        restoreLocation()
    }

    @Suppress("DEPRECATION")
    private fun ensureLocationOn() {
        runCatching {
            val cr = ctx.contentResolver
            val mode = Settings.Secure.getInt(cr, Settings.Secure.LOCATION_MODE, Settings.Secure.LOCATION_MODE_OFF)
            if (mode == Settings.Secure.LOCATION_MODE_OFF) {
                Settings.Secure.putInt(cr, Settings.Secure.LOCATION_MODE, Settings.Secure.LOCATION_MODE_BATTERY_SAVING)
                restoreLocationMode = mode
            }
        }.onFailure { Log.w(TAG, "can't toggle location (WRITE_SECURE_SETTINGS not granted?)", it) }
    }

    @Suppress("DEPRECATION")
    private fun restoreLocation() {
        val m = restoreLocationMode ?: return
        restoreLocationMode = null
        runCatching { Settings.Secure.putInt(ctx.contentResolver, Settings.Secure.LOCATION_MODE, m) }
    }

    companion object { private const val TAG = "BleScan" }
}
