package com.nexuslights.panel.kiosk

import android.Manifest
import android.app.Activity
import android.app.ActivityManager
import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.provider.Settings
import android.util.Log
import com.nexuslights.panel.ui.MainActivity

/** Device-admin receiver; made device owner by scripts/provision.sh via `dpm set-device-owner`. */
class AdminReceiver : DeviceAdminReceiver()

/**
 * Locks the tablet down to this app when it is the device owner:
 * it's the permanent home screen, runs in lock-task mode (no home / recents /
 * notification shade), has no lock screen, and gets its runtime permissions
 * granted without prompts. Everything here is undoable over adb (see
 * android/README.md, "Getting the tablet back").
 */
object Kiosk {
    private const val TAG = "Kiosk"

    fun admin(ctx: Context) = ComponentName(ctx, AdminReceiver::class.java)
    private fun dpm(ctx: Context) = ctx.getSystemService(DevicePolicyManager::class.java)

    fun isDeviceOwner(ctx: Context) = dpm(ctx).isDeviceOwnerApp(ctx.packageName)
    fun isAdminActive(ctx: Context) = dpm(ctx).isAdminActive(admin(ctx))

    private const val PREFS = "kiosk"
    /** Set by "Exit kiosk mode" so the next app start doesn't re-lock immediately. */
    fun isSuspended(ctx: Context) = ctx.getSharedPreferences(PREFS, 0).getBoolean("suspended", false)
    private fun setSuspended(ctx: Context, v: Boolean) =
        ctx.getSharedPreferences(PREFS, 0).edit().putBoolean("suspended", v).apply()

    /** Idempotent; called on every app start. */
    fun applyPolicies(ctx: Context) {
        if (!isDeviceOwner(ctx)) { Log.i(TAG, "not device owner; kiosk policies skipped"); return }
        val dpm = dpm(ctx)
        val admin = admin(ctx)
        val pkg = ctx.packageName
        runCatching {
            for (p in listOf(
                Manifest.permission.CAMERA,
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.WRITE_EXTERNAL_STORAGE,
                Manifest.permission.READ_EXTERNAL_STORAGE,
            )) dpm.setPermissionGrantState(admin, pkg, p, DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED)
        }.onFailure { Log.w(TAG, "grant permissions", it) }
        // Keep Wi-Fi up with the screen off so HomeKit stays reachable.
        @Suppress("DEPRECATION")
        runCatching { dpm.setGlobalSetting(admin, Settings.Global.WIFI_SLEEP_POLICY, Settings.Global.WIFI_SLEEP_POLICY_NEVER.toString()) }
        runCatching { dpm.setKeyguardDisabled(admin, true) }

        if (isSuspended(ctx)) return
        runCatching { dpm.setLockTaskPackages(admin, arrayOf(pkg)) }
        runCatching { dpm.setStatusBarDisabled(admin, true) }
        runCatching {
            val filter = IntentFilter(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME); addCategory(Intent.CATEGORY_DEFAULT)
            }
            dpm.addPersistentPreferredActivity(admin, filter, ComponentName(ctx, MainActivity::class.java))
        }.onFailure { Log.w(TAG, "set home", it) }
    }

    fun enterLockTask(activity: Activity) {
        if (!isDeviceOwner(activity) || isSuspended(activity)) return
        val am = activity.getSystemService(ActivityManager::class.java)
        if (am.lockTaskModeState == ActivityManager.LOCK_TASK_MODE_NONE) {
            runCatching { activity.startLockTask() }.onFailure { Log.w(TAG, "startLockTask", it) }
        }
    }

    /**
     * Maintenance: leave lock-task mode and open Android Settings. The app
     * re-locks the next time it's opened via [resume] (the Home button brings
     * it back since it's still the only launcher).
     */
    fun exitForMaintenance(activity: Activity) {
        setSuspended(activity, true)
        runCatching { activity.stopLockTask() }
        if (isDeviceOwner(activity)) {
            val dpm = dpm(activity)
            runCatching { dpm.setStatusBarDisabled(admin(activity), false) }
        }
        runCatching {
            activity.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    fun resume(activity: Activity) {
        if (!isSuspended(activity)) return
        setSuspended(activity, false)
        applyPolicies(activity)
        enterLockTask(activity)
    }
}
