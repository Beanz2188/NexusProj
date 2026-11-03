package com.nexuslights.panel.system

import android.content.Context
import android.os.Environment
import android.util.Log
import java.io.File

/**
 * Mirrors the app's state (HomeKit identity + light list) to
 * /sdcard/NexusPanel/backup so it survives an uninstall/reinstall.
 *
 * Why: the app is the tablet's device owner, and Android only lets an update
 * install over it if it's signed with the same key. A rebuild on another
 * machine has a different key, so scripts/update.sh falls back to
 * uninstall + reinstall, which wipes app data, and with it the Apple Home
 * pairing. With this backup the reinstalled app picks up the same pairing
 * and lights and Apple Home never notices.
 *
 * Nothing else is installed on the tablet to read it; storage permission is
 * granted by device-owner policy.
 */
object Backup {
    private const val TAG = "Backup"

    private fun dir(): File = File(Environment.getExternalStorageDirectory(), "NexusPanel/backup")

    fun save(ctx: Context, name: String, text: String) {
        runCatching {
            val d = dir()
            if (!d.exists() && !d.mkdirs()) return
            val tmp = File(d, "$name.tmp")
            tmp.writeText(text)
            tmp.renameTo(File(d, name))
        }.onFailure { Log.w(TAG, "backup of $name failed: ${it.message}") }
    }

    /** If [target] doesn't exist yet but a backup does, restore it. */
    fun restoreIfMissing(ctx: Context, name: String, target: File) {
        if (target.exists()) return
        runCatching {
            val src = File(dir(), name)
            if (src.exists()) {
                src.copyTo(target)
                Log.i(TAG, "restored $name from ${src.path}")
            }
        }.onFailure { Log.w(TAG, "restore of $name failed: ${it.message}") }
    }
}
