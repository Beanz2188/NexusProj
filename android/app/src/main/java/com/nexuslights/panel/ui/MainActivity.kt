package com.nexuslights.panel.ui

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.MotionEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.nexuslights.panel.PanelApp
import com.nexuslights.panel.kiosk.Kiosk
import com.nexuslights.panel.sensors.ScreenState
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlin.math.log10

class MainActivity : ComponentActivity() {
    private val app get() = PanelApp.of(this)
    private var swallowGesture = false

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        app.presence.refresh()
    }

    @Suppress("DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
        )
        WindowCompat.setDecorFitsSystemWindows(window, false)
        hideSystemBars()
        // This is the home screen: Back never leaves it.
        onBackPressedDispatcher.addCallback(this) { }

        app.ensureServiceRunning()
        requestMissingPermissions()

        setContent { PanelTheme { PanelRoot(app, this) } }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(app.presence.screen, app.presence.lux, app.settings.autoBrightness) { s, l, a -> Triple(s, l, a) }
                    .collect { (s, l, a) -> applyBrightness(s, l, a) }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        Kiosk.resume(this)
        Kiosk.enterLockTask(this)
        hideSystemBars()
        app.presence.userInteraction()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    /**
     * Any touch counts as activity. When the panel is dimmed or off, the first
     * touch only wakes it, so you can't toggle a light by accident in the dark.
     */
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
            val wasActive = app.presence.screen.value == ScreenState.ACTIVE
            app.presence.userInteraction()
            if (!wasActive) { swallowGesture = true; return true }
        }
        if (swallowGesture) {
            if (ev.actionMasked == MotionEvent.ACTION_UP || ev.actionMasked == MotionEvent.ACTION_CANCEL) swallowGesture = false
            return true
        }
        return super.dispatchTouchEvent(ev)
    }

    private fun hideSystemBars() {
        val c = WindowInsetsControllerCompat(window, window.decorView)
        c.hide(WindowInsetsCompat.Type.systemBars())
        c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    private fun requestMissingPermissions() {
        if (Kiosk.isDeviceOwner(this)) return // granted by policy
        val missing = listOf(Manifest.permission.CAMERA, Manifest.permission.ACCESS_FINE_LOCATION)
            .filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) permissionLauncher.launch(missing.toTypedArray())
    }

    private fun applyBrightness(state: ScreenState, lux: Float?, auto: Boolean) {
        val lp = window.attributes
        lp.screenBrightness = when (state) {
            ScreenState.AMBIENT -> 0.01f
            ScreenState.OFF -> 0.01f
            ScreenState.ACTIVE -> if (auto && lux != null) luxToBrightness(lux)
            else WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        }
        window.attributes = lp
    }

    /** ~8% in a dark room, full brightness in daylight, log curve in between. */
    private fun luxToBrightness(lux: Float): Float =
        (0.08f + 0.92f * (log10(lux + 1f) / log10(3000f))).coerceIn(0.08f, 1f)
}

@Composable
private fun PanelRoot(app: PanelApp, activity: Activity) {
    val lights by app.lights.lights.collectAsState()
    val hk by app.homekit.status.collectAsState()
    val motion by app.presence.motion.collectAsState()
    val device by app.device.state.collectAsState()
    val homeName by app.settings.homeName.collectAsState()
    val screen by app.presence.screen.collectAsState()

    var openLight by rememberSaveable { mutableStateOf<String?>(null) }
    var settingsOpen by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(screen) {
        if (screen == ScreenState.OFF) { openLight = null; settingsOpen = false }
    }

    Box(Modifier.fillMaxSize()) {
        AmbientBackground(lights)
        HomeScreen(
            homeName = homeName,
            lights = lights,
            homekit = hk,
            motion = motion,
            device = device,
            onToggle = { app.lights.toggle(it.config.address) },
            onOpenLight = { openLight = it.config.address },
            onScene = { app.lights.applyScene(it) },
            onAllOff = { app.lights.allOff() },
            onOpenSettings = { settingsOpen = true },
        )

        val detail = lights.firstOrNull { it.config.address == openLight }
        AnimatedVisibility(detail != null, enter = fadeIn() + scaleIn(initialScale = 0.94f), exit = fadeOut() + scaleOut(targetScale = 0.94f)) {
            // keep the last light while the exit animation runs
            val l = detail ?: return@AnimatedVisibility
            LightDetail(
                light = l,
                onClose = { openLight = null },
                onPower = { app.lights.setPower(l.config.address, it) },
                onBrightness = { app.lights.setBrightness(l.config.address, it) },
                onColor = { h, s -> app.lights.setColor(l.config.address, h, s) },
            )
        }

        AnimatedVisibility(settingsOpen, enter = slideInVertically { it / 6 } + fadeIn(), exit = slideOutVertically { it / 6 } + fadeOut()) {
            SettingsScreen(app, activity, onClose = { settingsOpen = false })
        }

        AnimatedVisibility(screen != ScreenState.ACTIVE, enter = fadeIn(), exit = fadeOut()) {
            AmbientClock(lights)
        }
    }
}
