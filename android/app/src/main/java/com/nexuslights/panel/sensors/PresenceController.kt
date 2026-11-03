package com.nexuslights.panel.sensors

import android.annotation.SuppressLint
import android.app.admin.DevicePolicyManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import com.nexuslights.panel.data.PanelSettings
import com.nexuslights.panel.data.PresenceMode
import com.nexuslights.panel.kiosk.Kiosk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlin.math.sqrt

enum class ScreenState { ACTIVE, AMBIENT, OFF }

/**
 * Decides when the screen is on, dimmed to a clock, or off, based on touches
 * and on whatever presence sensors are enabled:
 *  - front camera motion (CameraMotionDetector)
 *  - accelerometer: a knock or bump on the tablet / wall
 *  - proximity sensor: a hand waved over the top of the screen
 * and reports motion + ambient light level to the bridge (which can expose
 * them to Apple Home as "Wall Panel" sensors).
 */
class PresenceController(
    private val ctx: Context,
    private val settings: PanelSettings,
) : SensorEventListener {

    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val sm = ctx.getSystemService(SensorManager::class.java)
    private val pm = ctx.getSystemService(PowerManager::class.java)

    private val _screen = MutableStateFlow(ScreenState.ACTIVE)
    val screen: StateFlow<ScreenState> = _screen.asStateFlow()

    private val _lux = MutableStateFlow<Float?>(null)
    /** Smoothed ambient light, null if the tablet has no light sensor. */
    val lux: StateFlow<Float?> = _lux.asStateFlow()

    private val _motion = MutableStateFlow(false)
    /** True while presence was detected in the last [MOTION_HOLD_MS]. */
    val motion: StateFlow<Boolean> = _motion.asStateFlow()

    private val _charging = MutableStateFlow(true)
    val charging: StateFlow<Boolean> = _charging.asStateFlow()

    /** Which presence sources are actually running right now (for the settings screen). */
    private val _activeSources = MutableStateFlow<Set<String>>(emptySet())
    val activeSources: StateFlow<Set<String>> = _activeSources.asStateFlow()

    var onMotionChanged: ((Boolean) -> Unit)? = null
    var onLux: ((Float) -> Unit)? = null

    private val camera = CameraMotionDetector(ctx) { main.post { presence("camera") } }
    private var lastActivity = SystemClock.elapsedRealtime()
    private var lastMotion = 0L

    private val accel: Sensor? = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val prox: Sensor? = sm.getDefaultSensor(Sensor.TYPE_PROXIMITY)
    private val light: Sensor? = sm.getDefaultSensor(Sensor.TYPE_LIGHT)
    private var sensorsOn = false
    private val gravity = FloatArray(3)
    private var gravityInit = false
    private var lastKnock = 0L
    private var proxNear = false

    fun start() {
        light?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL) }
        ctx.registerReceiver(object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                when (i.action) {
                    Intent.ACTION_BATTERY_CHANGED ->
                        _charging.value = i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
                    Intent.ACTION_SCREEN_OFF -> _screen.value = ScreenState.OFF
                    Intent.ACTION_SCREEN_ON -> if (_screen.value == ScreenState.OFF) {
                        lastActivity = SystemClock.elapsedRealtime(); _screen.value = ScreenState.ACTIVE
                    }
                }
            }
        }, IntentFilter().apply {
            addAction(Intent.ACTION_BATTERY_CHANGED)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
        })
        scope.launch {
            combine(settings.presence, _charging, settings.sensitivity) { m, c, s -> Triple(m, c, s) }
                .collect { (mode, charging, sens) -> configure(mode, charging, sens) }
        }
        main.post(tick)
    }

    /** Re-apply presence settings, e.g. after the camera permission was granted. */
    fun refresh() = configure(settings.presence.value, _charging.value, settings.sensitivity.value)

    private fun configure(mode: PresenceMode, charging: Boolean, sensitivity: Int) {
        camera.sensitivity = sensitivity
        val wantCamera = when (mode) {
            PresenceMode.CAMERA -> true
            PresenceMode.AUTO -> charging
            else -> false
        } && hasCameraPermission()
        val wantSensors = mode != PresenceMode.OFF

        if (wantCamera) camera.start() else camera.stop()
        if (wantSensors && !sensorsOn) {
            accel?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
            prox?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL) }
            sensorsOn = true
        } else if (!wantSensors && sensorsOn) {
            accel?.let { sm.unregisterListener(this, it) }
            prox?.let { sm.unregisterListener(this, it) }
            sensorsOn = false; gravityInit = false
        }
        _activeSources.value = buildSet {
            if (wantCamera) add("Camera")
            if (wantSensors && accel != null) add("Knock")
            if (wantSensors && prox != null) add("Hand wave")
            if (light != null) add("Light sensor")
        }
        Log.i(TAG, "presence mode=$mode charging=$charging -> ${_activeSources.value}")
    }

    private fun hasCameraPermission() =
        ctx.checkSelfPermission(android.Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    /** A touch on the screen. */
    fun userInteraction() {
        lastActivity = SystemClock.elapsedRealtime()
        if (_screen.value != ScreenState.ACTIVE) wake()
    }

    /** Someone is there (sensor): wake the panel and tell the bridge. */
    private fun presence(source: String) {
        val now = SystemClock.elapsedRealtime()
        lastActivity = now
        lastMotion = now
        if (!_motion.value) { _motion.value = true; onMotionChanged?.invoke(true) }
        if (_screen.value != ScreenState.ACTIVE) {
            Log.d(TAG, "wake by $source")
            wake()
        }
    }

    @SuppressLint("WakelockTimeout")
    @Suppress("DEPRECATION")
    fun wake() {
        if (_screen.value == ScreenState.OFF || !pm.isInteractive) {
            val wl = pm.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP or PowerManager.ON_AFTER_RELEASE,
                "nexus:wake",
            )
            wl.acquire(5_000)
        }
        lastActivity = SystemClock.elapsedRealtime()
        _screen.value = ScreenState.ACTIVE
    }

    fun sleepNow() {
        _screen.value = ScreenState.OFF
        val dpm = ctx.getSystemService(DevicePolicyManager::class.java)
        if (Kiosk.isAdminActive(ctx)) runCatching { dpm.lockNow() }
    }

    private val tick = object : Runnable {
        override fun run() {
            val now = SystemClock.elapsedRealtime()
            val idle = now - lastActivity
            val idleLimit = settings.idleSeconds.value * 1000L
            val canTurnOff = settings.presence.value != PresenceMode.OFF && Kiosk.isAdminActive(ctx)
            when (_screen.value) {
                ScreenState.ACTIVE -> if (idle > idleLimit) {
                    if (settings.ambientClock.value || !canTurnOff) _screen.value = ScreenState.AMBIENT else sleepNow()
                }
                ScreenState.AMBIENT -> if (canTurnOff && idle > idleLimit + AMBIENT_MS) sleepNow()
                ScreenState.OFF -> {}
            }
            if (_motion.value && now - lastMotion > MOTION_HOLD_MS) {
                _motion.value = false; onMotionChanged?.invoke(false)
            }
            main.postDelayed(this, 1000)
        }
    }

    // ---- SensorEventListener ---------------------------------------------

    override fun onSensorChanged(e: SensorEvent) {
        when (e.sensor.type) {
            Sensor.TYPE_LIGHT -> {
                val v = e.values[0]
                val cur = _lux.value
                val smoothed = if (cur == null) v else cur * 0.7f + v * 0.3f
                _lux.value = smoothed
                onLux?.invoke(smoothed)
            }
            Sensor.TYPE_ACCELEROMETER -> {
                if (!gravityInit) { e.values.copyInto(gravity); gravityInit = true; return }
                for (i in 0..2) gravity[i] = 0.9f * gravity[i] + 0.1f * e.values[i]
                val x = e.values[0] - gravity[0]
                val y = e.values[1] - gravity[1]
                val z = e.values[2] - gravity[2]
                val mag = sqrt(x * x + y * y + z * z)
                val threshold = KNOCK_THRESHOLDS[settings.sensitivity.value.coerceIn(1, 5) - 1]
                val now = SystemClock.elapsedRealtime()
                if (mag > threshold && now - lastKnock > 800) { lastKnock = now; presence("knock") }
            }
            Sensor.TYPE_PROXIMITY -> {
                val near = e.values[0] < (e.sensor.maximumRange.coerceAtMost(5f))
                if (near && !proxNear) presence("proximity")
                proxNear = near
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    companion object {
        private const val TAG = "Presence"
        private const val AMBIENT_MS = 60_000L
        private const val MOTION_HOLD_MS = 30_000L
        /** m/s^2 of non-gravity acceleration that counts as a knock, by sensitivity 1..5. */
        private val KNOCK_THRESHOLDS = floatArrayOf(3.0f, 2.0f, 1.4f, 1.0f, 0.7f)
    }
}
