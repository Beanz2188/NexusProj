package com.nexuslights.panel

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import com.nexuslights.panel.bridge.BridgeClient
import com.nexuslights.panel.data.LightRepository
import com.nexuslights.panel.data.PanelSettings
import com.nexuslights.panel.kiosk.Kiosk
import com.nexuslights.panel.sensors.PresenceController
import com.nexuslights.panel.service.PanelService
import com.nexuslights.panel.system.DeviceStatus
import kotlinx.coroutines.launch

/** Holds the app-wide singletons. Started once per process. */
class PanelApp : Application() {
    lateinit var settings: PanelSettings; private set
    lateinit var bridge: BridgeClient; private set
    lateinit var lights: LightRepository; private set
    lateinit var presence: PresenceController; private set
    lateinit var device: DeviceStatus; private set

    private var started = false

    override fun onCreate() {
        super.onCreate()
        settings = PanelSettings(this)
        settings.importProvisioning(this)
        bridge = BridgeClient(
            this,
            url = { settings.bridgeUrl.value },
            token = { settings.token.value },
            onUrlDiscovered = { settings.setBridgeUrl(it) },
        )
        lights = LightRepository(bridge, settings)
        presence = PresenceController(this, settings)
        device = DeviceStatus(this)
        Kiosk.applyPolicies(this)
    }

    /** Called by PanelService once it's in the foreground. */
    fun startBackground() {
        if (started) return
        started = true
        Log.i("PanelApp", "starting; bridge ${settings.bridgeUrl.value.ifBlank { "(not set)" }}")
        presence.onMotionChanged = { bridge.reportPanel(motion = it) }
        presence.onLux = { bridge.reportPanel(lux = it) }
        presence.start()
        device.start()
        // Poll the bridge only while there's something on screen.
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main).launch {
            presence.screen.collect { bridge.setActive(it != com.nexuslights.panel.sensors.ScreenState.OFF) }
        }
    }

    fun ensureServiceRunning() {
        ContextCompat.startForegroundService(this, Intent(this, PanelService::class.java))
    }

    companion object {
        fun of(ctx: Context) = ctx.applicationContext as PanelApp
    }
}

/** Boot / app update: bring the service back up. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        PanelApp.of(context).ensureServiceRunning()
    }
}
