package com.nexuslights.panel.data

import android.content.Context
import android.util.Log
import com.nexuslights.panel.protocol.LightProtocol
import java.io.File

/**
 * Imports lights from the Mac's nexuslights config so nothing has to be
 * re-added by hand. scripts/provision.sh pushes ~/.config/nexuslights/lights.yaml to
 *   /sdcard/Android/data/com.nexuslights.panel/files/lights.yaml
 * (the app's own external dir: adb can write it, no storage permission needed).
 * It's read once at startup and renamed to lights.yaml.imported.
 */
object ConfigImport {
    private const val TAG = "ConfigImport"

    fun importPending(ctx: Context, repo: LightRepository) {
        val dir = ctx.getExternalFilesDir(null) ?: return
        val f = File(dir, "lights.yaml")
        if (!f.exists()) return
        runCatching {
            val parsed = parseLightsYaml(f.readText())
            parsed.forEach { cfg ->
                if (repo.config(cfg.address.uppercase()) == null) repo.addOrUpdate(cfg)
            }
            Log.i(TAG, "imported ${parsed.size} light(s) from ${f.path}")
            f.renameTo(File(dir, "lights.yaml.imported"))
        }.onFailure { Log.e(TAG, "import failed", it) }
    }

    /**
     * Tiny parser for the exact shape nexuslights/config.py writes:
     *
     *   lights:
     *     - address: AA:BB:CC:DD:EE:FF
     *       name: Desk Strip
     *       protocol: triones
     *
     * (yaml.safe_dump may quote values; quotes are stripped.)
     */
    fun parseLightsYaml(text: String): List<LightConfig> {
        val out = mutableListOf<LightConfig>()
        var cur = mutableMapOf<String, String>()
        fun flush() {
            val addr = cur["address"]; val proto = cur["protocol"]
            if (addr != null && proto != null && LightProtocol.info(proto) != null) {
                out += LightConfig(
                    addr.uppercase(), cur["name"] ?: addr, proto,
                    onDemand = cur["on_demand"]?.lowercase() == "true",
                )
            }
            cur = mutableMapOf()
        }
        var inLights = false
        for (raw in text.lines()) {
            val line = raw.substringBefore(" #").trimEnd()
            if (line.isBlank()) continue
            // top-level keys: only the "lights:" section matters (skip apple_tv etc.)
            if (!line.first().isWhitespace() && !line.startsWith("-")) {
                flush()
                inLights = line.startsWith("lights:")
                continue
            }
            if (!inLights) continue
            var t = line.trim()
            if (t.startsWith("- ")) { flush(); t = t.removePrefix("- ").trim() }
            val idx = t.indexOf(':')
            if (idx <= 0) continue
            val key = t.substring(0, idx).trim()
            val value = t.substring(idx + 1).trim().removeSurrounding("'").removeSurrounding("\"")
            if (value.isEmpty()) continue
            cur[key] = value
        }
        flush()
        return out
    }
}
