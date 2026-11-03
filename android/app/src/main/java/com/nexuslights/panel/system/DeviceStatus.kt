package com.nexuslights.panel.system

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.os.BatteryManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Battery and Wi-Fi, shown in the header since the status bar is hidden. */
class DeviceStatus(private val ctx: Context) {
    data class State(
        val batteryPct: Int = -1,
        val charging: Boolean = false,
        val wifi: Boolean = false,
        /** 0..4 */
        val wifiLevel: Int = 0,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    fun start() {
        ctx.registerReceiver(object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
                val status = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
                _state.value = _state.value.copy(
                    batteryPct = if (level >= 0 && scale > 0) level * 100 / scale else -1,
                    charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL,
                )
            }
        }, IntentFilter(Intent.ACTION_BATTERY_CHANGED))

        ctx.registerReceiver(object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) = refreshWifiLevel()
        }, IntentFilter(WifiManager.RSSI_CHANGED_ACTION))

        ctx.getSystemService(ConnectivityManager::class.java).registerNetworkCallback(
            NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(),
            object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) { _state.value = _state.value.copy(wifi = true); refreshWifiLevel() }
                override fun onLost(network: Network) { _state.value = _state.value.copy(wifi = false) }
            },
        )
    }

    @Suppress("DEPRECATION")
    private fun refreshWifiLevel() {
        val wm = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val rssi = wm.connectionInfo?.rssi ?: return
        _state.value = _state.value.copy(wifiLevel = WifiManager.calculateSignalLevel(rssi, 5))
    }
}
