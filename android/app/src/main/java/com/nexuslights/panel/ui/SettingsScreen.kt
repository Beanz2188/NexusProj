package com.nexuslights.panel.ui

import android.app.Activity
import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.BluetoothSearching
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.nexuslights.panel.BuildConfig
import com.nexuslights.panel.PanelApp
import com.nexuslights.panel.ble.BleScanner
import com.nexuslights.panel.data.LightConfig
import com.nexuslights.panel.data.LightIcon
import com.nexuslights.panel.data.LinkStatus
import com.nexuslights.panel.data.PresenceMode
import com.nexuslights.panel.kiosk.Kiosk
import com.nexuslights.panel.protocol.LightProtocol

@Composable
fun SettingsScreen(app: PanelApp, activity: Activity, onClose: () -> Unit) {
    BackHandler(onBack = onClose)
    val lights by app.lights.lights.collectAsState()
    val hk by app.homekit.status.collectAsState()
    val presence by app.settings.presence.collectAsState()
    val idle by app.settings.idleSeconds.collectAsState()
    val ambient by app.settings.ambientClock.collectAsState()
    val sensitivity by app.settings.sensitivity.collectAsState()
    val autoBrightness by app.settings.autoBrightness.collectAsState()
    val homeName by app.settings.homeName.collectAsState()
    val motion by app.presence.motion.collectAsState()
    val lux by app.presence.lux.collectAsState()
    val sources by app.presence.activeSources.collectAsState()
    val device by app.device.state.collectAsState()
    val bleOnDemand by app.settings.bleOnDemand.collectAsState()

    var editing by remember { mutableStateOf<LightConfig?>(null) }
    var adding by remember { mutableStateOf(false) }
    var renamingHome by remember { mutableStateOf(false) }
    var confirmReset by remember { mutableStateOf(false) }
    var confirmExit by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 22.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Settings", color = Palette.text, fontSize = 34.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                TextButton(onClick = onClose) { Text("Done", color = Palette.orange, fontSize = 17.sp, fontWeight = FontWeight.SemiBold) }
            }

            // ---- Apple Home ----
            GroupTitle("Apple Home")
            Group {
                if (hk.paired) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.CheckCircle, null, tint = Palette.green, modifier = Modifier.size(28.dp))
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text("Added to Apple Home", color = Palette.text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                            Text(
                                if (hk.running) "Bridge running at ${hk.address}" else (hk.error ?: "Bridge stopped"),
                                color = Palette.subText, fontSize = 14.sp,
                            )
                        }
                    }
                } else {
                    PairingCard(hk.setupUri, hk.pin, hk.running, hk.error)
                }
                Divider()
                ActionRow("Restart bridge") { app.homekit.restart("user") }
                Divider()
                ActionRow("Reset Apple Home pairing", destructive = true) { confirmReset = true }
            }

            // ---- Lights ----
            GroupTitle("Lights")
            Group {
                lights.forEachIndexed { i, l ->
                    if (i > 0) Divider()
                    NavRow(
                        title = l.config.name,
                        detail = "${l.config.protocolInfo.displayName} · " + when (l.link) {
                            LinkStatus.CONNECTED -> "Connected"
                            LinkStatus.CONNECTING -> "Connecting"
                            LinkStatus.DISCONNECTED -> "Waiting"
                            LinkStatus.FAILED -> "No response"
                            LinkStatus.IDLE -> "On demand"
                        },
                        onClick = { editing = l.config },
                    )
                }
                if (lights.isNotEmpty()) Divider()
                Row(
                    Modifier.fillMaxWidth().clickable { adding = true }.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Rounded.Add, null, tint = Palette.orange)
                    Spacer(Modifier.width(10.dp))
                    Text("Add Light…", color = Palette.orange, fontSize = 17.sp)
                }
                Divider()
                SwitchRow(
                    "Connect per command",
                    "Don't hold a Bluetooth link to every strip. Slower, but use it if lights keep showing No Response",
                    bleOnDemand,
                ) { app.settings.setBleOnDemand(it); app.ble.forceOnDemand = it }
                Divider()
                ActionRow("Reconnect all lights") { app.lights.retryAll() }
            }

            // ---- Presence & display ----
            GroupTitle("Motion & Display")
            Group {
                PresenceMode.entries.forEachIndexed { i, m ->
                    if (i > 0) Divider()
                    Row(
                        Modifier.fillMaxWidth().clickable { app.settings.setPresence(m) }.padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(m.label, color = Palette.text, fontSize = 17.sp)
                            Text(m.detail, color = Palette.subText, fontSize = 13.sp)
                        }
                        if (m == presence) Icon(Icons.Rounded.CheckCircle, null, tint = Palette.orange)
                    }
                }
                Divider()
                Column(Modifier.padding(16.dp)) {
                    Row {
                        Text("Sensitivity", color = Palette.text, fontSize = 17.sp, modifier = Modifier.weight(1f))
                        Text(listOf("Lowest", "Low", "Medium", "High", "Highest")[sensitivity - 1], color = Palette.subText, fontSize = 15.sp)
                    }
                    Slider(
                        value = sensitivity.toFloat(), onValueChange = { app.settings.setSensitivity(Math.round(it)) },
                        valueRange = 1f..5f, steps = 3,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(10.dp).clip(CircleShape).background(if (motion) Palette.green else Palette.faint))
                        Spacer(Modifier.width(8.dp))
                        Text(
                            (if (motion) "Motion detected" else "No motion") +
                                (lux?.let { " · ${it.toInt()} lux" } ?: "") +
                                (if (sources.isNotEmpty()) " · " + sources.joinToString() else ""),
                            color = Palette.subText, fontSize = 13.sp,
                        )
                    }
                }
                Divider()
                Column(Modifier.padding(16.dp)) {
                    Text("Dim after", color = Palette.text, fontSize = 17.sp)
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(15 to "15s", 30 to "30s", 45 to "45s", 60 to "1m", 120 to "2m", 300 to "5m").forEach { (sec, label) ->
                            Choice(label, selected = idle == sec) { app.settings.setIdleSeconds(sec) }
                        }
                    }
                }
                Divider()
                SwitchRow("Clock before screen off", "Shows a dim clock for a minute before turning off", ambient) {
                    app.settings.setAmbientClock(it)
                }
                Divider()
                SwitchRow("Auto brightness", "Uses the light sensor", autoBrightness) { app.settings.setAutoBrightness(it) }
            }

            // ---- Home ----
            GroupTitle("Home")
            Group { NavRow("Name", homeName) { renamingHome = true } }

            // ---- Device ----
            GroupTitle("Device")
            Group {
                InfoRow("Battery", if (device.batteryPct < 0) "—" else "${device.batteryPct}%" + if (device.charging) " · Charging" else "")
                Divider()
                InfoRow("Wi-Fi", if (device.wifi) "Connected" else "Not connected")
                Divider()
                InfoRow("Kiosk", if (Kiosk.isDeviceOwner(activity)) "Locked (device owner)" else "Not provisioned")
                Divider()
                InfoRow("Version", BuildConfig.VERSION_NAME)
            }

            GroupTitle("Maintenance")
            Group {
                ActionRow("Turn screen off now") { onClose(); app.presence.sleepNow() }
                Divider()
                ActionRow("Exit kiosk mode", destructive = true) { confirmExit = true }
            }
            Spacer(Modifier.height(40.dp))
        }
    }

    editing?.let { cfg ->
        EditLightDialog(
            cfg,
            onDismiss = { editing = null },
            onSave = { app.lights.addOrUpdate(it); editing = null },
            onRemove = { app.lights.remove(cfg.address); editing = null },
            onIdentify = { app.lights.identify(cfg.address) },
        )
    }
    if (adding) {
        AddLightDialog(
            app.scanner,
            known = lights.map { it.config.address }.toSet(),
            onDismiss = { app.scanner.stop(); adding = false },
            onAdd = { app.lights.addOrUpdate(it); app.scanner.stop(); adding = false },
        )
    }
    if (renamingHome) {
        TextDialog("Home name", homeName, onDismiss = { renamingHome = false }) {
            app.settings.setHomeName(it.ifBlank { "Home" }); renamingHome = false
        }
    }
    if (confirmReset) {
        ConfirmDialog(
            "Reset Apple Home pairing?",
            "The lights disappear from the Home app until you add the panel again with its new code.",
            "Reset", onDismiss = { confirmReset = false },
        ) { app.homekit.resetPairing(); confirmReset = false }
    }
    if (confirmExit) {
        ConfirmDialog(
            "Exit kiosk mode?",
            "Opens Android Settings for maintenance. Press Home to come back; the panel locks itself again.",
            "Exit", onDismiss = { confirmExit = false },
        ) { confirmExit = false; onClose(); Kiosk.exitForMaintenance(activity) }
    }
}

// ---------------------------------------------------------------------------

@Composable
private fun PairingCard(uri: String, pin: String, running: Boolean, error: String?) {
    val qr: ImageBitmap? = remember(uri) { if (uri.isBlank()) null else qrBitmap(uri, 560) }
    Column(Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Add to Apple Home", color = Palette.text, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        Text(
            "On your iPhone open Home → + → Add Accessory, and scan this code.\n" +
                "If the old NexusLights bridge from the Mac is still in Home, remove it first.",
            color = Palette.subText, fontSize = 14.sp, textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(16.dp))
        if (qr != null) {
            Box(Modifier.clip(RoundedCornerShape(18.dp)).background(Color.White).padding(14.dp)) {
                Image(qr, "HomeKit setup code", Modifier.size(220.dp))
            }
        }
        Spacer(Modifier.height(14.dp))
        Text(pin, color = Palette.text, fontSize = 30.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold, letterSpacing = 2.sp)
        Text(
            when {
                error != null -> error
                running -> "Waiting for Apple Home…"
                else -> "Starting bridge…"
            },
            color = if (error != null) Palette.red else Palette.subText, fontSize = 13.sp,
        )
    }
}

private fun qrBitmap(text: String, size: Int): ImageBitmap {
    val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, mapOf(EncodeHintType.MARGIN to 0))
    val pixels = IntArray(size * size) { i -> if (matrix.get(i % size, i / size)) 0xFF000000.toInt() else 0xFFFFFFFF.toInt() }
    return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888).asImageBitmap()
}

@Composable
private fun AddLightDialog(
    scanner: BleScanner,
    known: Set<String>,
    onDismiss: () -> Unit,
    onAdd: (LightConfig) -> Unit,
) {
    val results by scanner.results.collectAsState()
    val scanning by scanner.scanning.collectAsState()
    val error by scanner.error.collectAsState()
    var chosen by remember { mutableStateOf<BleScanner.Found?>(null) }
    var wifiNew by remember { mutableStateOf(false) }
    DisposableEffect(Unit) { scanner.start(); onDispose { scanner.stop() } }

    if (wifiNew) {
        EditLightDialog(
            LightConfig("", "Wi-Fi Light", "fluxled", icon = LightIcon.CEILING.id),
            isNew = true,
            onDismiss = { wifiNew = false },
            onSave = onAdd,
            onRemove = {},
            onIdentify = {},
        )
        return
    }

    chosen?.let { f ->
        EditLightDialog(
            LightConfig(f.address, f.name ?: "Light", f.guess?.id ?: "triones"),
            isNew = true,
            onDismiss = { chosen = null },
            onSave = onAdd,
            onRemove = {},
            onIdentify = {},
        )
        return
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add Light") },
        text = {
            Column(Modifier.heightIn(max = 420.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (scanning) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    else Icon(Icons.Rounded.BluetoothSearching, null, tint = Palette.subText, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        error ?: if (scanning) "Scanning for nearby strips…" else "Scan finished",
                        color = if (error != null) Palette.red else Palette.subText, fontSize = 14.sp,
                    )
                }
                Spacer(Modifier.height(8.dp))
                LazyColumn {
                    items(results.filter { it.address !in known }, key = { it.address }) { f ->
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { chosen = f }.padding(vertical = 10.dp, horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(f.name ?: "Unnamed device", color = if (f.guess != null) Palette.text else Palette.subText, fontSize = 16.sp)
                                Text(
                                    "${f.address} · ${f.rssi} dBm" + (f.guess?.let { " · ${it.displayName}" } ?: ""),
                                    color = Palette.subText, fontSize = 12.sp,
                                )
                            }
                            if (f.guess != null) Text("Light", color = Palette.orange, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { scanner.start() }, enabled = !scanning) { Text("Scan again") }
        },
        dismissButton = {
            Row {
                TextButton(onClick = { wifiNew = true }) { Text("Wi-Fi light…") }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EditLightDialog(
    cfg: LightConfig,
    isNew: Boolean = false,
    onDismiss: () -> Unit,
    onSave: (LightConfig) -> Unit,
    onRemove: () -> Unit,
    onIdentify: () -> Unit,
) {
    var address by remember { mutableStateOf(cfg.address) }
    var name by remember { mutableStateOf(cfg.name) }
    var room by remember { mutableStateOf(cfg.room) }
    var protocol by remember { mutableStateOf(cfg.protocol) }
    var icon by remember { mutableStateOf(cfg.icon) }
    var onDemand by remember { mutableStateOf(cfg.onDemand) }
    val isWifi = LightProtocol.info(protocol)?.transport == LightProtocol.Transport.WIFI
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isNew) "New Light" else cfg.name) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (isNew && (isWifi || address.isBlank())) {
                    OutlinedTextField(
                        address, { address = it.trim() }, singleLine = true,
                        label = { Text(if (isWifi) "IP address" else "Bluetooth address") },
                    )
                } else {
                    Text(cfg.address, color = Palette.subText, fontSize = 13.sp)
                }
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(room, { room = it }, label = { Text("Room") }, singleLine = true)
                Spacer(Modifier.height(14.dp))
                Text("Protocol", color = Palette.subText, fontSize = 13.sp)
                Spacer(Modifier.height(6.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    LightProtocol.ALL.forEach { p -> Choice(p.displayName, protocol == p.id) { protocol = p.id } }
                }
                if (!isWifi) {
                    Spacer(Modifier.height(6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Connect on demand", color = Palette.text, fontSize = 15.sp)
                            Text("For lights that drop idle links or flash on reconnect", color = Palette.subText, fontSize = 12.sp)
                        }
                        Switch(onDemand, { onDemand = it })
                    }
                }
                Spacer(Modifier.height(14.dp))
                Text("Icon", color = Palette.subText, fontSize = 13.sp)
                Spacer(Modifier.height(6.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    LightIcon.entries.forEach { ic ->
                        Box(
                            Modifier.size(40.dp).clip(CircleShape)
                                .background(if (icon == ic.id) Palette.orange else Palette.cardRow)
                                .clickable { icon = ic.id },
                            contentAlignment = Alignment.Center,
                        ) { Icon(iconFor(ic.id), ic.label, tint = Color.White, modifier = Modifier.size(22.dp)) }
                    }
                }
                if (!isNew) {
                    Spacer(Modifier.height(16.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = onIdentify) { Text("Blink") }
                        TextButton(onClick = onRemove) { Text("Remove", color = Palette.red) }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = address.isNotBlank(),
                onClick = {
                    onSave(cfg.copy(
                        address = address, name = name.ifBlank { cfg.name }, room = room.ifBlank { "Home" },
                        protocol = protocol, icon = icon, onDemand = onDemand && !isWifi,
                    ))
                },
            ) { Text(if (isNew) "Add" else "Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun TextDialog(title: String, initial: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var v by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { OutlinedTextField(v, { v = it }, singleLine = true) },
        confirmButton = { TextButton(onClick = { onSave(v.trim()) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun ConfirmDialog(title: String, body: String, action: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(body) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(action, color = Palette.red) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

// ---- grouped-list building blocks (iOS Settings look) ----------------------

@Composable
private fun GroupTitle(text: String) {
    Text(
        text.uppercase(), color = Palette.subText, fontSize = 13.sp,
        modifier = Modifier.padding(start = 16.dp, top = 26.dp, bottom = 8.dp),
    )
}

@Composable
private fun Group(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.widthIn(max = 720.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Palette.card),
        content = content,
    )
}

@Composable
private fun Divider() = HorizontalDivider(Modifier.padding(start = 16.dp), thickness = 0.5.dp, color = Palette.separator)

@Composable
private fun NavRow(title: String, detail: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, color = Palette.text, fontSize = 17.sp, modifier = Modifier.weight(1f))
        Text(detail, color = Palette.subText, fontSize = 15.sp)
        Icon(Icons.Rounded.ChevronRight, null, tint = Palette.faint)
    }
}

@Composable
private fun InfoRow(title: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp)) {
        Text(title, color = Palette.text, fontSize = 17.sp, modifier = Modifier.weight(1f))
        Text(value, color = Palette.subText, fontSize = 15.sp)
    }
}

@Composable
private fun ActionRow(title: String, destructive: Boolean = false, onClick: () -> Unit) {
    Text(
        title, color = if (destructive) Palette.red else Palette.orange, fontSize = 17.sp,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 14.dp),
    )
}

@Composable
private fun SwitchRow(title: String, detail: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = Palette.text, fontSize = 17.sp)
            Text(detail, color = Palette.subText, fontSize = 13.sp)
        }
        Switch(
            checked, onChange,
            colors = SwitchDefaults.colors(checkedTrackColor = Palette.green, checkedThumbColor = Color.White),
        )
    }
}

@Composable
private fun Choice(label: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        label,
        color = if (selected) Color.Black else Palette.text,
        fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(if (selected) Palette.orange else Palette.cardRow)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    )
}
