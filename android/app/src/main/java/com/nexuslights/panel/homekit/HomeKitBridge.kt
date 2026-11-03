package com.nexuslights.panel.homekit

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.HandlerThread
import android.util.Base64
import android.util.Log
import com.nexuslights.panel.data.LightRepository
import com.nexuslights.panel.data.LightState
import com.nexuslights.panel.system.Backup
import io.github.hapjava.accessories.HomekitAccessory
import io.github.hapjava.accessories.LightSensorAccessory
import io.github.hapjava.accessories.LightbulbAccessory
import io.github.hapjava.accessories.MotionSensorAccessory
import io.github.hapjava.accessories.optionalcharacteristic.AccessoryWithBrightness
import io.github.hapjava.accessories.optionalcharacteristic.AccessoryWithColor
import io.github.hapjava.characteristics.HomekitCharacteristicChangeCallback
import io.github.hapjava.server.HomekitAccessoryCategories
import io.github.hapjava.server.HomekitAuthInfo
import io.github.hapjava.server.impl.HomekitRoot
import io.github.hapjava.server.impl.HomekitServer
import io.github.hapjava.server.impl.crypto.HAPSetupCodeUtils
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.io.File
import java.math.BigInteger
import java.net.Inet4Address
import java.net.InetAddress
import java.util.concurrent.CompletableFuture
import kotlin.math.roundToInt

/**
 * The tablet's own HomeKit bridge (replaces `nexus-lights bridge` on the Mac).
 * Uses HAP-Java, a pure-Java HomeKit Accessory Protocol server, and exposes:
 *  - every configured light as a Lightbulb (on / brightness / hue / saturation)
 *  - the panel's presence detection as a Motion Sensor
 *  - the panel's ambient light sensor as a Light Sensor
 * so Apple Home automations can use the tablet as a sensor too.
 */
class HomeKitBridge(
    private val ctx: Context,
    private val repo: LightRepository,
    private val bridgeName: () -> String,
) {
    data class Status(
        val running: Boolean = false,
        val paired: Boolean = false,
        val pin: String = "",
        val setupUri: String = "",
        val address: String? = null,
        val error: String? = null,
    )

    private val _status = MutableStateFlow(Status())
    val status: StateFlow<Status> = _status.asStateFlow()

    private val thread = HandlerThread("homekit").apply { start() }
    private val h = Handler(thread.looper)
    private val auth = AuthStore(ctx, File(ctx.filesDir, "homekit.json")) { publishStatus() }

    private var server: HomekitServer? = null
    private var root: HomekitRoot? = null
    private var boundAddress: InetAddress? = null
    private val lightAccessories = LinkedHashMap<String, LightAccessory>()
    private val motion = MotionAccessory(auth.idFor("panel:motion"))
    private val lux = LuxAccessory(auth.idFor("panel:lux"))

    private val multicastLock = (ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager)
        .createMulticastLock("nexus-homekit").apply { setReferenceCounted(false) }

    private val repoListener = LightRepository.ChangeListener { address, old, new ->
        if (old == null || new == null) {
            // a light was added/removed: accessory list changed -> rebuild
            h.removeCallbacks(restartRunnable); h.postDelayed(restartRunnable, 1500)
        } else {
            lightAccessories[address]?.notifyChanged(old, new)
        }
    }
    private val restartRunnable = Runnable { restart("accessories changed") }

    fun start() {
        repo.addListener(repoListener)
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        cm.registerNetworkCallback(
            NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(),
            object : ConnectivityManager.NetworkCallback() {
                override fun onLinkPropertiesChanged(network: Network, lp: LinkProperties) {
                    val ip = lp.linkAddresses.map { it.address }.firstOrNull { it is Inet4Address }
                    if (ip != null && ip != boundAddress) h.post { restart("network: $ip") }
                }
                override fun onLost(network: Network) { h.post { stopInternal(); publishStatus() } }
            },
            h,
        )
        h.post { restart("start") }
    }

    fun restart(reason: String) {
        if (Thread.currentThread() !== thread) { h.post { restart(reason) }; return }
        Log.i(TAG, "(re)starting HomeKit bridge: $reason")
        stopInternal()
        val ip = wifiIpv4()
        if (ip == null) {
            _status.value = _status.value.copy(running = false, error = "Waiting for Wi-Fi", address = null)
            return
        }
        try {
            multicastLock.acquire()
            val srv = HomekitServer(ip, PORT)
            val r = srv.createBridge(
                auth, bridgeName().take(60), HomekitAccessoryCategories.BRIDGES,
                "NexusLights", "Nexus Panel", auth.serial, "1.0", "1.0",
            )
            lightAccessories.clear()
            for (cfg in repo.configs.value) {
                val aid = auth.idFor(cfg.address)
                val acc = if (cfg.supportsColor) ColorLightAccessory(aid, cfg.address, repo) else LightAccessory(aid, cfg.address, repo)
                lightAccessories[cfg.address] = acc
                r.addAccessory(acc)
            }
            r.addAccessory(motion)
            r.addAccessory(lux)
            // Tell iOS to re-read the accessory list when it changes.
            r.setConfigurationIndex(auth.configIndexFor(lightAccessories.keys.sorted().joinToString()))
            r.start()
            server = srv; root = r; boundAddress = ip
            _status.value = _status.value.copy(running = true, error = null, address = "${ip.hostAddress}:$PORT")
            publishStatus()
            Log.i(TAG, "HomeKit bridge up at ${ip.hostAddress}:$PORT paired=${auth.hasUser()} setup code ${auth.getPin()}")
        } catch (e: Throwable) {
            Log.e(TAG, "HomeKit bridge failed", e)
            stopInternal()
            _status.value = _status.value.copy(running = false, error = e.message ?: e.javaClass.simpleName)
            h.postDelayed({ restart("retry after error") }, 30_000)
        }
    }

    private fun stopInternal() {
        runCatching { root?.stop() }
        runCatching { server?.stop() }
        root = null; server = null; boundAddress = null
        runCatching { multicastLock.release() }
    }

    /** Forget every paired iPhone/Home hub and start over with a fresh identity. */
    fun resetPairing() = h.post {
        stopInternal()
        auth.reset()
        restart("pairing reset")
    }

    fun setMotion(detected: Boolean) = motion.set(detected)
    fun setLux(value: Float) = lux.set(value)

    private fun publishStatus() {
        _status.value = _status.value.copy(
            paired = auth.hasUser(),
            pin = auth.getPin(),
            setupUri = HAPSetupCodeUtils.getSetupURI(auth.getPin().replace("-", ""), auth.getSetupId(), HomekitAccessoryCategories.BRIDGES),
        )
    }

    private fun wifiIpv4(): InetAddress? {
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        @Suppress("DEPRECATION")
        val nets = cm.allNetworks
        for (n in nets) {
            val caps = cm.getNetworkCapabilities(n) ?: continue
            if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) continue
            cm.getLinkProperties(n)?.linkAddresses?.map { it.address }?.firstOrNull { it is Inet4Address }?.let { return it }
        }
        return null
    }

    companion object {
        private const val TAG = "HomeKit"
        const val PORT = 51827 // the Mac's HAP-python bridge used 51826
    }
}

// ---------------------------------------------------------------------------
// Persistent HomeKit identity. Losing this file un-pairs the bridge.
// ---------------------------------------------------------------------------

private class AuthStore(
    private val ctx: Context,
    private val file: File,
    private val onUsersChanged: () -> Unit,
) : HomekitAuthInfo {
    private var json: JSONObject = load()

    @Synchronized private fun load(): JSONObject {
        Backup.restoreIfMissing(ctx, file.name, file)
        val existing = runCatching { JSONObject(file.readText()) }.getOrNull()
        if (existing != null && existing.has("pin")) return existing
        return fresh(existing?.optJSONObject("ids"), existing?.optInt("nextId", 2) ?: 2)
    }

    private fun fresh(ids: JSONObject?, nextId: Int) = JSONObject()
        .put("pin", HomekitServer.generatePin())
        .put("setupId", HAPSetupCodeUtils.generateSetupId())
        .put("mac", HomekitServer.generateMac())
        .put("salt", HomekitServer.generateSalt().toString())
        .put("key", Base64.encodeToString(HomekitServer.generateKey(), Base64.NO_WRAP))
        .put("serial", "NP" + (100000..999999).random())
        .put("users", JSONObject())
        .put("ids", ids ?: JSONObject())
        .put("nextId", nextId)
        .put("configs", JSONObject())
        .also { json = it; save(it) }

    @Synchronized private fun save(j: JSONObject = json) {
        val text = j.toString()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(text)
        tmp.renameTo(file)
        Backup.save(ctx, file.name, text)
    }

    @Synchronized fun reset() {
        // keep accessory ids stable so automations survive a re-pair
        fresh(json.optJSONObject("ids"), json.optInt("nextId", 2))
        onUsersChanged()
    }

    val serial: String get() = json.getString("serial")
    override fun getPin(): String = json.getString("pin")
    override fun getSetupId(): String = json.getString("setupId")
    override fun getMac(): String = json.getString("mac")
    override fun getSalt(): BigInteger = BigInteger(json.getString("salt"))
    override fun getPrivateKey(): ByteArray = Base64.decode(json.getString("key"), Base64.NO_WRAP)

    @Synchronized override fun createUser(username: String, publicKey: ByteArray, isAdmin: Boolean) {
        json.getJSONObject("users").put(username, JSONObject()
            .put("key", Base64.encodeToString(publicKey, Base64.NO_WRAP)).put("admin", isAdmin))
        save(); onUsersChanged()
    }

    @Synchronized override fun createUser(username: String, publicKey: ByteArray) = createUser(username, publicKey, true)

    @Synchronized override fun removeUser(username: String) {
        json.getJSONObject("users").remove(username); save(); onUsersChanged()
    }

    @Synchronized override fun getUserPublicKey(username: String): ByteArray? =
        json.getJSONObject("users").optJSONObject(username)?.getString("key")?.let { Base64.decode(it, Base64.NO_WRAP) }

    @Synchronized override fun listUsers(): Collection<String> = json.getJSONObject("users").keys().asSequence().toList()

    @Synchronized override fun userIsAdmin(username: String): Boolean =
        json.getJSONObject("users").optJSONObject(username)?.optBoolean("admin", true) ?: false

    @Synchronized override fun hasUser(): Boolean = json.getJSONObject("users").length() > 0

    /** Stable HomeKit accessory id per light (aid 1 is the bridge itself). */
    @Synchronized fun idFor(key: String): Int {
        val ids = json.getJSONObject("ids")
        if (ids.has(key)) return ids.getInt(key)
        val id = json.optInt("nextId", 2)
        ids.put(key, id); json.put("nextId", id + 1); save()
        return id
    }

    /** Bumps HomeKit's configuration number whenever the set of lights changes. */
    @Synchronized fun configIndexFor(signature: String): Int {
        val cur = json.optString("configSig")
        var idx = json.optInt("configIdx", 1)
        if (cur != signature) {
            idx = if (idx >= 65535) 1 else idx + 1
            json.put("configSig", signature).put("configIdx", idx); save()
        }
        return idx
    }
}

// ---------------------------------------------------------------------------
// Accessories
// ---------------------------------------------------------------------------

private fun <T> done(v: T): CompletableFuture<T> = CompletableFuture.completedFuture(v)
private fun ok(): CompletableFuture<Void> = CompletableFuture.completedFuture(null)

private abstract class PanelAccessory(private val aid: Int, private val label: String) : HomekitAccessory {
    override fun getId() = aid
    override fun getName(): CompletableFuture<String> = done(label)
    override fun getSerialNumber(): CompletableFuture<String> = done("NP-$aid")
    override fun getModel(): CompletableFuture<String> = done("Nexus Panel")
    override fun getManufacturer(): CompletableFuture<String> = done("NexusLights")
    override fun getFirmwareRevision(): CompletableFuture<String> = done("1.0")
    override fun identify() {}
}

/** On/off + brightness. Used as-is for brightness-only lights (the Wi-Fi dimmer). */
private open class LightAccessory(
    aid: Int,
    protected val address: String,
    protected val repo: LightRepository,
) : PanelAccessory(aid, repo.config(address)?.name ?: address),
    LightbulbAccessory, AccessoryWithBrightness {

    @Volatile private var onCb: HomekitCharacteristicChangeCallback? = null
    @Volatile private var briCb: HomekitCharacteristicChangeCallback? = null

    protected val s get() = repo.state(address)
    protected val src = LightRepository.Source.HOMEKIT

    override fun getName(): CompletableFuture<String> = done(repo.config(address)?.name ?: address)
    override fun identify() = repo.identify(address)

    override fun getLightbulbPowerState(): CompletableFuture<Boolean> = done(s.on)
    override fun setLightbulbPowerState(powerState: Boolean): CompletableFuture<Void> {
        repo.setPower(address, powerState, src); return ok()
    }
    override fun subscribeLightbulbPowerState(callback: HomekitCharacteristicChangeCallback) { onCb = callback }
    override fun unsubscribeLightbulbPowerState() { onCb = null }

    override fun getBrightness(): CompletableFuture<Int> = done(s.brightness)
    override fun setBrightness(value: Int): CompletableFuture<Void> {
        // HomeKit sends brightness 0 when you drag to the bottom; treat as off.
        if (value <= 0) repo.setPower(address, false, src) else repo.setBrightness(address, value, src)
        return ok()
    }
    override fun subscribeBrightness(callback: HomekitCharacteristicChangeCallback) { briCb = callback }
    override fun unsubscribeBrightness() { briCb = null }

    /** Push changes made on the panel (or by scenes) to every paired Apple device. */
    open fun notifyChanged(old: LightState, new: LightState) {
        if (old.on != new.on) onCb?.changed()
        if (old.brightness != new.brightness) briCb?.changed()
    }
}

/** Full color light: adds hue + saturation. */
private class ColorLightAccessory(aid: Int, address: String, repo: LightRepository) :
    LightAccessory(aid, address, repo), AccessoryWithColor {

    @Volatile private var hueCb: HomekitCharacteristicChangeCallback? = null
    @Volatile private var satCb: HomekitCharacteristicChangeCallback? = null

    override fun getHue(): CompletableFuture<Double> = done(s.hue.toDouble())
    override fun setHue(value: Double): CompletableFuture<Void> { repo.setHue(address, value.toFloat(), src); return ok() }
    override fun subscribeHue(callback: HomekitCharacteristicChangeCallback) { hueCb = callback }
    override fun unsubscribeHue() { hueCb = null }

    override fun getSaturation(): CompletableFuture<Double> = done((s.saturation * 100.0).roundToInt().toDouble())
    override fun setSaturation(value: Double): CompletableFuture<Void> {
        repo.setSaturation(address, (value / 100.0).toFloat(), src); return ok()
    }
    override fun subscribeSaturation(callback: HomekitCharacteristicChangeCallback) { satCb = callback }
    override fun unsubscribeSaturation() { satCb = null }

    override fun notifyChanged(old: LightState, new: LightState) {
        super.notifyChanged(old, new)
        if (old.hue != new.hue) hueCb?.changed()
        if (old.saturation != new.saturation) satCb?.changed()
    }
}

private class MotionAccessory(aid: Int) : PanelAccessory(aid, "Panel Motion"), MotionSensorAccessory {
    @Volatile private var detected = false
    @Volatile private var cb: HomekitCharacteristicChangeCallback? = null
    fun set(v: Boolean) { if (v != detected) { detected = v; cb?.changed() } }
    override fun getMotionDetected(): CompletableFuture<Boolean> = done(detected)
    override fun subscribeMotionDetected(callback: HomekitCharacteristicChangeCallback) { cb = callback }
    override fun unsubscribeMotionDetected() { cb = null }
}

private class LuxAccessory(aid: Int) : PanelAccessory(aid, "Panel Light Level"), LightSensorAccessory {
    @Volatile private var lux = 1.0
    @Volatile private var cb: HomekitCharacteristicChangeCallback? = null
    private var lastSent = 1.0
    fun set(v: Float) {
        lux = v.toDouble().coerceIn(0.0001, 100000.0)
        // Don't spam iOS: only notify on a >20% change.
        if (kotlin.math.abs(lux - lastSent) > lastSent * 0.2 + 1) { lastSent = lux; cb?.changed() }
    }
    override fun getCurrentAmbientLightLevel(): CompletableFuture<Double> = done(lux)
    override fun subscribeCurrentAmbientLightLevel(callback: HomekitCharacteristicChangeCallback) { cb = callback }
    override fun unsubscribeCurrentAmbientLightLevel() { cb = null }
}
