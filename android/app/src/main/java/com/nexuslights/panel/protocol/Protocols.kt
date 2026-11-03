package com.nexuslights.panel.protocol

import java.util.UUID

/**
 * Byte-for-byte port of nexuslights/protocols/*.py. Pure Kotlin, no Android
 * dependency, so it's unit-tested on the JVM against the same vectors as the
 * Python tests (see ProtocolsTest). If you tune a frame in Python against real
 * hardware, mirror it here.
 */
sealed class LightProtocol(
    val id: String,
    val displayName: String,
    val service: UUID,
    val writeCharacteristic: UUID,
    val nameHints: List<String>,
) {
    abstract fun powerOn(): ByteArray
    abstract fun powerOff(): ByteArray
    abstract fun setRgb(r: Int, g: Int, b: Int): ByteArray

    /** Separate brightness command, or null when brightness is folded into RGB. */
    open fun setBrightness(percent: Int): ByteArray? = null

    protected fun clamp(v: Int, lo: Int = 0, hi: Int = 255) = v.coerceIn(lo, hi)
    protected fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    /** Triones / LEDBLE / MELK / LEDnetWF / Happy Lighting / QHM. */
    object Triones : LightProtocol(
        id = "triones",
        displayName = "Triones",
        service = uuid16(0xFFD5),
        writeCharacteristic = uuid16(0xFFD9),
        nameHints = listOf("triones", "ledble", "led-ble", "melk", "lednetwf", "happy lighting", "qhm"),
    ) {
        override fun powerOn() = bytes(0xCC, 0x23, 0x33)
        override fun powerOff() = bytes(0xCC, 0x24, 0x33)
        override fun setRgb(r: Int, g: Int, b: Int) =
            bytes(0x56, clamp(r), clamp(g), clamp(b), 0x00, 0xF0, 0xAA)

        fun setWhite(percent: Int) =
            bytes(0x56, 0x00, 0x00, 0x00, clamp(Math.round(percent * 255 / 100f)), 0x0F, 0xAA)
    }

    /** ELK-BLEDOM / ELK-BULB / Longra. */
    object ElkBledom : LightProtocol(
        id = "elkbledom",
        displayName = "ELK-BLEDOM",
        service = uuid16(0xFFF0),
        writeCharacteristic = uuid16(0xFFF3),
        nameHints = listOf("elk-ble", "elk-bulb", "elk-lamp", "longra", "led-ble"),
    ) {
        override fun powerOn() = bytes(0x7E, 0x00, 0x04, 0xF0, 0x00, 0x01, 0xFF, 0x00, 0xEF)
        override fun powerOff() = bytes(0x7E, 0x00, 0x04, 0x00, 0x00, 0x00, 0xFF, 0x00, 0xEF)
        override fun setRgb(r: Int, g: Int, b: Int) =
            bytes(0x7E, 0x00, 0x05, 0x03, clamp(r), clamp(g), clamp(b), 0x00, 0xEF)

        override fun setBrightness(percent: Int) =
            bytes(0x7E, 0x00, 0x01, clamp(percent, 0, 100), 0xFF, 0xFF, 0xFF, 0x00, 0xEF)
    }

    companion object {
        /** Same order as the Python REGISTRY, so "led-ble" resolves to triones in both. */
        val ALL: List<LightProtocol> by lazy { listOf(Triones, ElkBledom) }

        fun byId(id: String): LightProtocol =
            ALL.firstOrNull { it.id == id } ?: throw IllegalArgumentException(
                "Unknown protocol '$id'. Known protocols: ${ALL.joinToString { it.id }}"
            )

        fun guess(deviceName: String?): LightProtocol? {
            if (deviceName.isNullOrBlank()) return null
            val lowered = deviceName.lowercase()
            return ALL.firstOrNull { p -> p.nameHints.any { lowered.contains(it) } }
        }

        /** Find a protocol by an advertised service UUID (when the name gives nothing away). */
        fun byService(uuids: Collection<UUID>): LightProtocol? =
            ALL.firstOrNull { p -> uuids.any { it == p.service || it == p.writeCharacteristic } }

        fun uuid16(short: Int): UUID =
            UUID.fromString(String.format("0000%04x-0000-1000-8000-00805f9b34fb", short))
    }
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
