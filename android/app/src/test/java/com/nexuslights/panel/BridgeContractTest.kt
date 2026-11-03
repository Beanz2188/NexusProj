package com.nexuslights.panel

import com.nexuslights.panel.bridge.BridgeClient
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.net.ServerSocket
import java.net.URL
import java.util.concurrent.TimeUnit

/**
 * The tablet's BridgeClient against the real Python panel API
 * (tests/panel_contract_server.py: real dashboard + HomeKit accessory
 * classes, fake lights). Catches the two sides drifting apart on field
 * names, ids, units or auth. Skipped when python3 + aiohttp + HAP-python
 * aren't available.
 */
class BridgeContractTest {
    private var proc: Process? = null
    private var port = 0

    @Before fun startServer() {
        val repo = generateSequence(File("").absoluteFile) { it.parentFile }
            .firstOrNull { File(it, "tests/panel_contract_server.py").exists() }
        assumeTrue("repo root not found", repo != null)
        port = ServerSocket(0).use { it.localPort }
        val p = runCatching {
            ProcessBuilder("python3", "tests/panel_contract_server.py", port.toString())
                .directory(repo).redirectErrorStream(true).start()
        }.getOrNull()
        assumeTrue("python3 not available", p != null)
        proc = p
        val reader = p!!.inputStream.bufferedReader()
        var ready = false
        val deadline = System.currentTimeMillis() + 15_000
        while (System.currentTimeMillis() < deadline) {
            val line = reader.readLine() ?: break
            if (line == "READY") { ready = true; break }
        }
        assumeTrue("contract server didn't start (missing aiohttp / HAP-python?)", ready)
    }

    @After fun stopServer() {
        proc?.destroy()
        proc?.waitFor(5, TimeUnit.SECONDS)
    }

    private fun client(token: String = "contract-token") =
        BridgeClient(null, url = { "http://127.0.0.1:$port" }, token = { token }, onUrlDiscovered = {})

    private fun waitFor(what: String, cond: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 8_000
        while (System.currentTimeMillis() < deadline) {
            if (cond()) return
            Thread.sleep(50)
        }
        throw AssertionError("timed out waiting for $what")
    }

    private fun calls(): List<String> {
        val arr = JSONArray(URL("http://127.0.0.1:$port/_calls").readText())
        return (0 until arr.length()).map { arr.getString(it) }
    }

    @Test fun readsLightsAndSwitches() {
        val c = client()
        assertEquals("Connected: 2 light(s)", runBlocking { c.test() })
        c.refreshNow()
        waitFor("snapshot") { c.snapshot.value.lights.size == 2 }
        val (desk, hall) = c.snapshot.value.lights
        assertEquals("11", desk.id); assertEquals("Desk Strip", desk.name); assertTrue(desk.color)
        assertEquals("12", hall.id); assertFalse(hall.color); assertEquals(null, hall.hue)
        assertEquals(listOf(BridgeClient.RemoteSwitch("20", "TV Ambilight", false)), c.snapshot.value.switches)
        assertEquals(BridgeClient.Health.CONNECTED, c.status.value.health)
    }

    @Test fun commandsReachTheLights() {
        val c = client()
        c.refreshNow()
        waitFor("first poll") { c.snapshot.value.lights.size == 2 }
        c.sendLight("11", mapOf("on" to true, "brightness" to 50, "hue" to 0.0, "saturation" to 100.0))
        waitFor("desk on") { c.snapshot.value.lights.firstOrNull { it.id == "11" }?.on == true }
        val desk = c.snapshot.value.lights.first { it.id == "11" }
        assertEquals(50, desk.brightness); assertEquals(0f, desk.hue); assertEquals(100f, desk.saturation)
        assertEquals(listOf("desk on", "desk rgb 128 0 0"), calls())

        c.sendLight("12", mapOf("on" to true, "brightness" to 30))
        waitFor("hall") { calls().size >= 4 }
        assertEquals(listOf("hall on", "hall brightness 30"), calls().drop(2))

        c.refreshNow()
        waitFor("switch list") { c.snapshot.value.switches.isNotEmpty() }
        c.sendSwitch("20", true)
        waitFor("switch") { c.snapshot.value.switches.firstOrNull()?.on == true }
    }

    @Test fun commandBeforeFirstPollStillShowsUp() {
        val c = client()
        c.sendLight("11", mapOf("on" to true))
        waitFor("desk on") { c.snapshot.value.lights.firstOrNull { it.id == "11" }?.on == true }
    }

    @Test fun rapidChangesMergeIntoFewRequests() {
        val c = client()
        c.refreshNow()
        waitFor("first poll") { c.snapshot.value.lights.size == 2 }
        c.sendLight("11", mapOf("on" to true))
        for (b in 1..30) c.sendLight("11", mapOf("brightness" to b))
        waitFor("final brightness") { c.snapshot.value.lights.firstOrNull { it.id == "11" }?.brightness == 30 }
        // far fewer than 31 round trips: pending changes merge while one is in flight
        assertTrue("sent ${calls().size} light commands", calls().size < 15)
    }

    @Test fun wrongTokenIsReported() {
        val c = client("nope")
        assertEquals("Wrong token", runBlocking { c.test() })
        c.refreshNow()
        waitFor("unauthorized") { c.status.value.health == BridgeClient.Health.UNAUTHORIZED }
    }

    @Test fun urlHelpers() {
        assertEquals("http://192.168.70.104:8080", BridgeClient.normalizeUrl("192.168.70.104"))
        assertEquals("http://192.168.70.104:9000", BridgeClient.normalizeUrl(" http://192.168.70.104:9000/ "))
        assertEquals("http://10.0.0.9:8080", BridgeClient.replaceHost("http://192.168.70.104:8080", "10.0.0.9"))
        assertEquals("", BridgeClient.normalizeUrl(""))
    }
}
