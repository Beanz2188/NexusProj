package com.nexuslights.panel.ui

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nexuslights.panel.BuildConfig
import com.nexuslights.panel.PanelApp
import com.nexuslights.panel.bridge.BridgeClient
import com.nexuslights.panel.data.Light
import com.nexuslights.panel.data.LightCustom
import com.nexuslights.panel.data.LightIcon
import com.nexuslights.panel.data.LinkStatus
import com.nexuslights.panel.data.PresenceMode
import com.nexuslights.panel.kiosk.Kiosk
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(app: PanelApp, activity: Activity, onClose: () -> Unit) {
    BackHandler(onBack = onClose)
    val lights by app.lights.lights.collectAsState()
    val bridge by app.bridge.status.collectAsState()
    val bridgeUrl by app.settings.bridgeUrl.collectAsState()
    val token by app.settings.token.collectAsState()
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
    val scope = rememberCoroutineScope()

    var editing by remember { mutableStateOf<Light?>(null) }
    var editUrl by remember { mutableStateOf(false) }
    var editToken by remember { mutableStateOf(false) }
    var renamingHome by remember { mutableStateOf(false) }
    var confirmExit by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<String?>(null) }

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

            // ---- Bridge ----
            GroupTitle("Bridge")
            Group {
                val ok = bridge.health == BridgeClient.Health.CONNECTED
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        if (ok) Icons.Rounded.CheckCircle else Icons.Rounded.ErrorOutline, null,
                        tint = if (ok) Palette.green else Palette.red, modifier = Modifier.size(28.dp),
                    )
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(
                            when (bridge.health) {
                                BridgeClient.Health.CONNECTED -> "Connected to the bridge"
                                BridgeClient.Health.CONNECTING -> "Connecting…"
                                BridgeClient.Health.NOT_CONFIGURED -> "Not set up"
                                BridgeClient.Health.UNAUTHORIZED -> "Wrong token"
                                BridgeClient.Health.API_DISABLED -> "Panel API unavailable"
                                BridgeClient.Health.UNREACHABLE -> "Can't reach the bridge"
                            },
                            color = Palette.text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            "The Mac runs the lights and Apple Home; this panel is a remote for it. " + bridge.detail,
                            color = Palette.subText, fontSize = 13.sp,
                        )
                    }
                }
                Divider()
                NavRow("Address", bridgeUrl.ifBlank { "Not set" }) { editUrl = true }
                Divider()
                NavRow("Token", if (token.isBlank()) "Not set" else "••••" + token.takeLast(4)) { editToken = true }
                Divider()
                ActionRow(testResult ?: "Test connection") {
                    testResult = "Testing…"
                    scope.launch { testResult = app.bridge.test(); app.bridge.refreshNow() }
                }
            }

            // ---- Lights ----
            GroupTitle("Lights")
            Group {
                if (lights.isEmpty()) {
                    InfoRow("No lights", if (bridge.health == BridgeClient.Health.CONNECTED) "Add them on the Mac" else "—")
                }
                lights.forEachIndexed { i, l ->
                    if (i > 0) Divider()
                    NavRow(
                        title = l.config.name,
                        detail = "${l.config.room} · " + when (l.link) {
                            LinkStatus.CONNECTED -> "Connected"
                            LinkStatus.IDLE -> "On demand"
                            LinkStatus.FAILED -> "No response"
                            LinkStatus.UNKNOWN -> "—"
                        },
                        onClick = { editing = l },
                    )
                }
            }
            Text(
                "Lights are added and removed on the Mac (nexus-lights add / remove). Here you choose how they look on the panel.",
                color = Palette.subText, fontSize = 13.sp, modifier = Modifier.padding(start = 16.dp, top = 8.dp, end = 16.dp),
            )

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

    editing?.let { l ->
        EditLightDialog(
            l,
            onDismiss = { editing = null },
            onSave = { app.settings.setCustomization(l.config.id, it); editing = null },
        )
    }
    if (editUrl) {
        TextDialog("Bridge address", bridgeUrl, hint = "e.g. 192.168.70.104:8080", onDismiss = { editUrl = false }) {
            app.settings.setBridgeUrl(BridgeClient.normalizeUrl(it)); editUrl = false; app.bridge.refreshNow()
        }
    }
    if (editToken) {
        TextDialog("Bridge token", token, hint = "from ~/.config/nexuslights/panel_token on the Mac", onDismiss = { editToken = false }) {
            app.settings.setToken(it.trim()); editToken = false; app.bridge.refreshNow()
        }
    }
    if (renamingHome) {
        TextDialog("Home name", homeName, onDismiss = { renamingHome = false }) {
            app.settings.setHomeName(it.ifBlank { "Home" }); renamingHome = false
        }
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EditLightDialog(light: Light, onDismiss: () -> Unit, onSave: (LightCustom) -> Unit) {
    var room by remember { mutableStateOf(light.config.room) }
    var icon by remember { mutableStateOf(light.config.icon) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(light.config.name) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    "${light.config.protocol} · " + if (light.config.supportsColor) "color" else "brightness only",
                    color = Palette.subText, fontSize = 13.sp,
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(room, { room = it }, label = { Text("Room") }, singleLine = true)
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
            }
        },
        confirmButton = { TextButton(onClick = { onSave(LightCustom(room.ifBlank { "Home" }, icon)) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun TextDialog(title: String, initial: String, hint: String? = null, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var v by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(v, { v = it }, singleLine = true)
                hint?.let { Text(it, color = Palette.subText, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp)) }
            }
        },
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
        Text(detail, color = Palette.subText, fontSize = 15.sp, maxLines = 1)
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
