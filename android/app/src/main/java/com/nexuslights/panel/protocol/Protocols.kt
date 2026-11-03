package com.nexuslights.panel.protocol

import java.util.UUID
import kotlin.math.floor

/**
 * Byte-for-byte port of the Python protocol modules in nexuslights/protocols
 * (air-bridge branch, i.e. the versions confirmed against the real strips).
 * Pure Kotlin, no Android dependency, so ProtocolsTest checks it on the JVM
 * against vectors generated from the Python code. If a frame gets tuned in
 * Python, mirror it here.
 *
 * Instances are per-light (LedNetWfIotwf keeps a sequence counter), so use
 * [LightProtocol.create]; [LightProtocol.ALL] is for metadata only.
 */
abstract class LightProtocol(
    val id: String,
    val displayName: String,
    val transport: Transport = Transport.BLE,
    /** Advertised service, used only to guess the protocol of an unnamed device. */
    val service: UUID? = null,
    val writeCharacteristic: UUID? = null,
    /** Must be subscribed before the device accepts writes (LEDnetWF, ELK-BLEDDM). */
    val notifyCharacteristic: UUID? = null,
    /** Send a GATT Write Request (acknowledged) instead of a Write Command. */
    val writeWithResponse: Boolean = false,
    /** The chip's power_off cuts its BLE radio: dim to 0 instead (MrStar). */
    val powerOffDisconnects: Boolean = false,
    /** setRgb() already scales with brightness; otherwise also send setBrightness(). */
    val colorIncludesBrightness: Boolean = true,
    val supportsColor: Boolean = true,
    val nameHints: List<String> = emptyList(),
) {
    enum class Transport { BLE, WIFI }

    abstract fun powerOn(): ByteArray
    abstract fun powerOff(): ByteArray
    abstract fun setRgb(r: Int, g: Int, b: Int): ByteArray
    /** 0..100, or null when this protocol has no brightness command. */
    open fun setBrightness(percent: Int): ByteArray? = null

    companion object {
        /** Same order as the Python REGISTRY so name guessing resolves identically. */
        val ALL: List<LightProtocol> by lazy {
            listOf(Triones(), ElkBledom(), ElkBledomNotify(), LedNetWf(), LedNetWfIotwf(), VcBleLight(), MrStar(), FluxLed())
        }

        fun create(id: String): LightProtocol = when (id) {
            "triones" -> Triones()
            "elkbledom" -> ElkBledom()
            "elkbledom-notify" -> ElkBledomNotify()
            "lednetwf" -> LedNetWf()
            "lednetwf-iotwf" -> LedNetWfIotwf()
            "vcblelight" -> VcBleLight()
            "mrstar" -> MrStar()
            "fluxled" -> FluxLed()
            else -> throw IllegalArgumentException(
                "Unknown protocol '$id'. Known protocols: ${ALL.joinToString { it.id }}"
            )
        }

        fun info(id: String): LightProtocol? = ALL.firstOrNull { it.id == id }

        fun guess(deviceName: String?): LightProtocol? {
            if (deviceName.isNullOrBlank()) return null
            val lowered = deviceName.lowercase()
            return ALL.firstOrNull { p -> p.nameHints.any { lowered.contains(it) } }
        }

        fun byService(uuids: Collection<UUID>): LightProtocol? =
            ALL.firstOrNull { p -> p.service != null && uuids.any { it == p.service } }

        fun uuid16(short: Int): UUID =
            UUID.fromString(String.format("0000%04x-0000-1000-8000-00805f9b34fb", short))
    }
}

internal fun clamp(v: Int, lo: Int = 0, hi: Int = 255) = v.coerceIn(lo, hi)
internal fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

/** Python's round(): half to even. */
internal fun pyRound(x: Double): Int = Math.rint(x).toInt()

/** Python's colorsys.rgb_to_hsv on 0..255 ints -> (h, s, v) in 0..1. */
internal fun rgbToHsv(r: Int, g: Int, b: Int): Triple<Double, Double, Double> {
    val rf = clamp(r) / 255.0; val gf = clamp(g) / 255.0; val bf = clamp(b) / 255.0
    val maxc = maxOf(rf, gf, bf); val minc = minOf(rf, gf, bf)
    if (minc == maxc) return Triple(0.0, 0.0, maxc)
    val s = (maxc - minc) / maxc
    val rc = (maxc - rf) / (maxc - minc)
    val gc = (maxc - gf) / (maxc - minc)
    val bc = (maxc - bf) / (maxc - minc)
    var h = when {
        rf == maxc -> bc - gc
        gf == maxc -> 2.0 + rc - bc
        else -> 4.0 + gc - rc
    }
    h /= 6.0
    h -= floor(h) // Python float % 1.0
    return Triple(h, s, maxc)
}

/** Triones / LEDBLE / MELK / Happy Lighting / QHM. */
class Triones : LightProtocol(
    id = "triones", displayName = "Triones",
    service = uuid16(0xFFD5), writeCharacteristic = uuid16(0xFFD9),
    nameHints = listOf("triones", "ledble", "led-ble", "melk", "happy lighting", "qhm"),
) {
    override fun powerOn() = bytes(0xCC, 0x23, 0x33)
    override fun powerOff() = bytes(0xCC, 0x24, 0x33)
    override fun setRgb(r: Int, g: Int, b: Int) = bytes(0x56, clamp(r), clamp(g), clamp(b), 0x00, 0xF0, 0xAA)
    fun setWhite(percent: Int) = bytes(0x56, 0, 0, 0, clamp(pyRound(percent * 255 / 100.0)), 0x0F, 0xAA)
}

/** ELK-BLEDOM / ELK-BULB / Longra. */
open class ElkBledom(
    id: String = "elkbledom",
    displayName: String = "ELK-BLEDOM",
    notify: UUID? = null,
    writeWithResponse: Boolean = false,
) : LightProtocol(
    id = id, displayName = displayName,
    service = uuid16(0xFFF0), writeCharacteristic = uuid16(0xFFF3),
    notifyCharacteristic = notify, writeWithResponse = writeWithResponse,
    nameHints = listOf("elk-ble", "elk-bulb", "elk-lamp", "longra", "led-ble"),
) {
    override fun powerOn() = bytes(0x7E, 0x00, 0x04, 0xF0, 0x00, 0x01, 0xFF, 0x00, 0xEF)
    override fun powerOff() = bytes(0x7E, 0x00, 0x04, 0x00, 0x00, 0x00, 0xFF, 0x00, 0xEF)
    override fun setRgb(r: Int, g: Int, b: Int) = bytes(0x7E, 0x00, 0x05, 0x03, clamp(r), clamp(g), clamp(b), 0x00, 0xEF)
    override fun setBrightness(percent: Int): ByteArray? =
        bytes(0x7E, 0x00, 0x01, clamp(percent, 0, 100), 0xFF, 0xFF, 0xFF, 0x00, 0xEF)
}

/**
 * ELK-BLEDDM variant: ignores writes until fff4 notifications are on, and
 * drops fire-and-forget writes on weak links, so writes need a response.
 * Indistinguishable by name, so it's only chosen explicitly.
 */
class ElkBledomNotify : ElkBledom(
    id = "elkbledom-notify", displayName = "ELK-BLEDDM",
    notify = uuid16(0xFFF4), writeWithResponse = true,
)

private val LEDNETWF_HEADER = intArrayOf(0x00, 0x00, 0x80, 0x00, 0x00, 0x0D, 0x0E, 0x0B)

/** LEDnetWF (Zengge/Magic Hue, GATT service 0xffff). */
class LedNetWf : LightProtocol(
    id = "lednetwf", displayName = "LEDnetWF",
    service = uuid16(0xFFFF), writeCharacteristic = uuid16(0xFF01), notifyCharacteristic = uuid16(0xFF02),
    nameHints = listOf("lednetwf"),
) {
    private fun frame(vararg payload: Int): ByteArray {
        val checksum = payload.sum() and 0xFF
        return bytes(*LEDNETWF_HEADER, *payload, checksum)
    }
    override fun powerOn() = frame(0x3B, 0x23, 0, 0, 0, 0, 0, 0, 0, 0x32, 0, 0)
    override fun powerOff() = frame(0x3B, 0x24, 0, 0, 0, 0, 0, 0, 0, 0x32, 0, 0)
    override fun setRgb(r: Int, g: Int, b: Int): ByteArray {
        val (h, s, v) = rgbToHsv(r, g, b)
        return frame(0x3B, 0xA1, pyRound(h * 360 / 2), pyRound(s * 100), pyRound(v * 100), 0, 0, 0, 0, 0, 0, 0)
    }
}

/** LEDnetWF "IOTWF" firmware (Surplife/Zengge): 0x71 power frames + sequence counter. */
class LedNetWfIotwf : LightProtocol(
    id = "lednetwf-iotwf", displayName = "LEDnetWF IOTWF",
    service = null, // same 0xffff service as classic lednetwf; tell them apart by name
    writeCharacteristic = uuid16(0xFF01), notifyCharacteristic = uuid16(0xFF02),
    nameHints = listOf("iotwf"),
) {
    private var seq = 0
    @Synchronized private fun nextSeq(): Int { seq = if (seq >= 255) 1 else seq + 1; return seq }

    private fun frame(vararg inner: Int): ByteArray {
        val checksum = inner.sum() and 0xFF
        val length = inner.size + 1
        return bytes(0x00, nextSeq(), 0x80, 0x00, 0x00, length, length + 1, 0x0B, *inner, checksum)
    }
    override fun powerOn() = frame(0x71, 0x23, 0x0F)
    override fun powerOff() = frame(0x71, 0x24, 0x0F)
    override fun setRgb(r: Int, g: Int, b: Int): ByteArray {
        val (h, s, v) = rgbToHsv(r, g, b)
        return frame(0x3B, 0xA1, pyRound(h * 360 / 2), pyRound(s * 100), pyRound(v * 100), 0, 0, 0, 0, 0, 0, 0)
    }
}

/** VC-BLELIGHT (AMill lamps, Raingel app). */
class VcBleLight : LightProtocol(
    id = "vcblelight", displayName = "VC-BLELIGHT",
    service = uuid16(0xAE30), writeCharacteristic = uuid16(0xAE01),
    nameHints = listOf("vc-blelight"),
) {
    override fun powerOn() = bytes(0x01, 0x01)
    override fun powerOff() = bytes(0x01, 0x00)
    override fun setRgb(r: Int, g: Int, b: Int) = bytes(0x03, clamp(r), clamp(g), clamp(b), 0, 0, 0)
    override fun setBrightness(percent: Int): ByteArray? = bytes(0x02, clamp(percent, 0, 100))
}

/** MrStar ("GATT--DEMO"): hue/sat-only color, separate 0..1000 brightness, power_off kills BLE. */
class MrStar : LightProtocol(
    id = "mrstar", displayName = "MrStar",
    service = null, writeCharacteristic = uuid16(0xFFF3),
    writeWithResponse = true, powerOffDisconnects = true, colorIncludesBrightness = false,
    nameHints = listOf("gatt--demo"),
) {
    override fun powerOn() = bytes(0xBC, 0x01, 0x01, 0x01, 0x55)
    override fun powerOff() = bytes(0xBC, 0x01, 0x01, 0x00, 0x55)
    override fun setRgb(r: Int, g: Int, b: Int): ByteArray {
        val (h, s, _) = rgbToHsv(r, g, b)
        val hue = (h * 360).toInt()
        val sat = (s * 1000).toInt()
        return bytes(0xBC, 0x04, 0x06, hue / 256, hue % 256, sat / 256, sat % 256, 0x00, 0x00, 0x55)
    }
    override fun setBrightness(percent: Int): ByteArray? {
        val value = clamp(percent, 0, 100) * 10
        return bytes(0xBC, 0x05, 0x06, value / 256, value % 256, 0, 0, 0, 0, 0x55)
    }
}

/**
 * Magic Home / Zengge Wi-Fi dimmer (flux_led LEDENET 8-byte protocol, TCP
 * port 5577). The address is the controller's IP. Frames match what
 * flux_led 1.2.0 sends for turnOn / turnOff / setWarmWhite255 on the
 * "Dimmable" models (0x17, 0x21, 0x41...), which is how the Mac's
 * wifi_light.py drives it. Brightness-only: no color.
 */
class FluxLed : LightProtocol(
    id = "fluxled", displayName = "Magic Home Wi-Fi",
    transport = Transport.WIFI,
    colorIncludesBrightness = false, supportsColor = false,
) {
    private fun msg(vararg v: Int) = bytes(*v, v.sum() and 0xFF)
    fun stateQuery() = msg(0x81, 0x8A, 0x8B)
    override fun powerOn() = msg(0x71, 0x23, 0x0F)
    override fun powerOff() = msg(0x71, 0x24, 0x0F)
    override fun setRgb(r: Int, g: Int, b: Int) = msg(0x31, clamp(r), clamp(g), clamp(b), 0x00, 0xF0, 0x0F)
    override fun setBrightness(percent: Int): ByteArray? =
        msg(0x31, 0, 0, 0, clamp(pyRound(clamp(percent, 0, 100) * 255 / 100.0)), 0x0F, 0x0F)

    companion object { const val PORT = 5577 }
}

/** HSV (as used by HomeKit and the UI) -> 0..255 RGB. */
object ColorMath {
    fun hsvToRgb(hueDeg: Float, sat: Float, value: Float): Triple<Int, Int, Int> {
        val h = ((hueDeg % 360f) + 360f) % 360f / 60f
        val s = sat.coerceIn(0f, 1f)
        val v = value.coerceIn(0f, 1f)
        val i = h.toInt()
        val f = h - i
        val p = v * (1 - s)
        val q = v * (1 - s * f)
        val t = v * (1 - s * (1 - f))
        val (r, g, b) = when (i % 6) {
            0 -> Triple(v, t, p)
            1 -> Triple(q, v, p)
            2 -> Triple(p, v, t)
            3 -> Triple(p, q, v)
            4 -> Triple(t, p, v)
            else -> Triple(v, p, q)
        }
        return Triple(Math.round(r * 255), Math.round(g * 255), Math.round(b * 255))
    }
}
