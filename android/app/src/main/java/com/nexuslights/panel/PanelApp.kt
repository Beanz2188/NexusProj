package com.nexuslights.panel

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import com.nexuslights.panel.ble.BleController
import com.nexuslights.panel.ble.BleScanner
import com.nexuslights.panel.data.ConfigImport
import com.nexuslights.panel.data.LightRepository
import com.nexuslights.panel.data.PanelSettings
import com.nexuslights.panel.homekit.HomeKitBridge
import com.nexuslights.panel.kiosk.Kiosk
import com.nexuslights.panel.sensors.PresenceController
import com.nexuslights.panel.service.PanelService
import com.nexuslights.panel.system.DeviceStatus
import com.nexuslights.panel.wifi.WifiLightController

/** Holds the app-wide singletons. Started once per process. */
class PanelApp : Application() {
    lateinit var settings: PanelSettings; private set
    lateinit var ble: BleController; private set
    lateinit var wifi: WifiLightController; private set
    lateinit var scanner: BleScanner; private set
    lateinit var lights: LightRepository; private set
    lateinit var homekit: HomeKitBridge; private set
    lateinit var presence: PresenceController; private set
    lateinit var device: DeviceStatus; private set

    private var started = false

    override fun onCreate() {
        super.onCreate()
        settings = PanelSettings(this)
        ble = BleController(this)
        ble.forceOnDemand = settings.bleOnDemand.value
        wifi = WifiLightController()
        scanner = BleScanner(this)
        lights = LightRepository(this, ble, wifi)
        homekit = HomeKitBridge(this, lights) { settings.homeName.value }
        presence = PresenceController(this, settings)
        device = DeviceStatus(this)
        ConfigImport.importPending(this, lights)
        Kiosk.applyPolicies(this)
    }

    /** Called by PanelService once it's in the foreground. */
    fun startBackground() {
        if (started) return
        started = true
        Log.i("PanelApp", "starting background services")
        presence.onMotionChanged = { homekit.setMotion(it) }
        presence.onLux = { homekit.setLux(it) }
        presence.start()
        homekit.start()
        device.start()
    }

    fun ensureServiceRunning() {
        ContextCompat.startForegroundService(this, Intent(this, PanelService::class.java))
    }

    companion object {
        fun of(ctx: Context) = ctx.applicationContext as PanelApp
    }
}

/** Boot / app update: bring the service (and with it the HomeKit bridge) back up. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        PanelApp.of(context).ensureServiceRunning()
    }
}
