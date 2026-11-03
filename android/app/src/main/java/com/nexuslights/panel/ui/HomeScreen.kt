package com.nexuslights.panel.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.BatteryChargingFull
import androidx.compose.material.icons.rounded.BatteryFull
import androidx.compose.material.icons.rounded.Battery3Bar
import androidx.compose.material.icons.rounded.Battery1Bar
import androidx.compose.material.icons.rounded.DirectionsWalk
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Lightbulb
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material.icons.rounded.WifiOff
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nexuslights.panel.data.Light
import com.nexuslights.panel.data.LinkStatus
import com.nexuslights.panel.data.Scene
import com.nexuslights.panel.data.Scenes
import com.nexuslights.panel.homekit.HomeKitBridge
import com.nexuslights.panel.system.DeviceStatus
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun rememberNow(): Long {
    val now by produceState(System.currentTimeMillis()) {
        while (true) {
            value = System.currentTimeMillis()
            delay(1000 - (value % 1000) + 5)
        }
    }
    return now
}

/** Wallpaper: dark gradient with a soft glow in the colors of the lights that are on. */
@Composable
fun AmbientBackground(lights: List<Light>) {
    val on = lights.filter { it.state.on }
    val target = if (on.isEmpty()) Color(0xFF3B2F25) else {
        val cs = on.map { it.state.uiColor() }
        Color(cs.map { it.red }.average().toFloat(), cs.map { it.green }.average().toFloat(), cs.map { it.blue }.average().toFloat())
    }
    val glow by animateColorAsState(target, tween(1200), label = "glow")
    val glowAlpha by animateFloatAsState(if (on.isEmpty()) 0.28f else 0.42f, tween(1200), label = "glowA")
    Box(
        Modifier
            .fillMaxSize()
            .drawBehind {
                drawRect(Brush.verticalGradient(listOf(Palette.bgTop, Palette.bgBottom)))
                drawRect(
                    Brush.radialGradient(
                        listOf(glow.copy(alpha = glowAlpha), Color.Transparent),
                        center = Offset(size.width * 0.15f, size.height * 0.05f),
                        radius = size.maxDimension * 0.75f,
                    )
                )
                drawRect(
                    Brush.radialGradient(
                        listOf(glow.copy(alpha = glowAlpha * 0.5f), Color.Transparent),
                        center = Offset(size.width * 0.95f, size.height * 0.9f),
                        radius = size.maxDimension * 0.6f,
                    )
                )
            }
    )
}

@Composable
fun HomeScreen(
    homeName: String,
    lights: List<Light>,
    homekit: HomeKitBridge.Status,
    motion: Boolean,
    device: DeviceStatus.State,
    onToggle: (Light) -> Unit,
    onOpenLight: (Light) -> Unit,
    onScene: (Scene) -> Unit,
    onAllOff: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val full: androidx.compose.foundation.lazy.grid.LazyGridItemSpanScope.() -> GridItemSpan = { GridItemSpan(maxLineSpan) }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 168.dp),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 22.dp, bottom = 40.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item(span = full) { Header(homeName, lights, motion, device, homekit, onOpenSettings) }
        item(span = full) { SummaryRow(lights, motion, homekit, onAllOff, onOpenSettings) }

        item(span = full) { SectionTitle("Scenes") }
        items(Scenes.ALL, key = { "scene-" + it.id }) { scene ->
            SceneTile(scene, active = isSceneActive(scene, lights), onClick = { onScene(scene) })
        }

        if (lights.isEmpty()) {
            item(span = full) { SectionTitle("Lights") }
            item(span = full) { EmptyLights(onOpenSettings) }
        } else {
            val rooms = lights.groupBy { it.config.room }
            for ((room, inRoom) in rooms) {
                item(span = full, key = "room-$room") {
                    SectionTitle(if (rooms.size == 1 && room == "Home") "Lights" else room)
                }
                lightItems(inRoom, onToggle, onOpenLight)
            }
        }
    }
}

private fun LazyGridScope.lightItems(lights: List<Light>, onToggle: (Light) -> Unit, onOpen: (Light) -> Unit) {
    items(lights, key = { it.config.address }) { light ->
        LightTile(light, onClick = { onToggle(light) }, onLongClick = { onOpen(light) })
    }
}

private fun isSceneActive(scene: Scene, lights: List<Light>): Boolean {
    if (lights.isEmpty()) return false
    return if (scene.state == null) lights.none { it.state.on } else lights.all { it.state == scene.state }
}

@Composable
private fun Header(
    homeName: String,
    lights: List<Light>,
    motion: Boolean,
    device: DeviceStatus.State,
    homekit: HomeKitBridge.Status,
    onOpenSettings: () -> Unit,
) {
    val now = rememberNow()
    val time = remember(now / 60000) { SimpleDateFormat("H:mm", Locale.getDefault()).format(Date(now)) }
    val date = remember(now / 3600000) { SimpleDateFormat("EEEE, d MMMM", Locale.getDefault()).format(Date(now)) }
    Column(Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusIcons(device, homekit)
            Spacer(Modifier.weight(1f))
            Text(date, color = Palette.subText, fontSize = 14.sp, fontWeight = FontWeight.Medium)
        }
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                homeName,
                color = Palette.text,
                fontSize = 36.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = (-0.6).sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f)
                    // Hidden settings: press and hold the home name.
                    .pointerInput(Unit) { detectTapGestures(onLongPress = { onOpenSettings() }) },
            )
            Text(time, color = Palette.text, fontSize = 44.sp, fontWeight = FontWeight.Light, letterSpacing = (-1).sp)
        }
    }
}

@Composable
private fun StatusIcons(device: DeviceStatus.State, homekit: HomeKitBridge.Status) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(
            if (device.wifi) Icons.Rounded.Wifi else Icons.Rounded.WifiOff, null,
            tint = if (device.wifi) Palette.subText else Palette.red, modifier = Modifier.size(16.dp),
        )
        if (device.batteryPct >= 0) {
            val icon = when {
                device.charging -> Icons.Rounded.BatteryChargingFull
                device.batteryPct > 70 -> Icons.Rounded.BatteryFull
                device.batteryPct > 30 -> Icons.Rounded.Battery3Bar
                else -> Icons.Rounded.Battery1Bar
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    icon, null, modifier = Modifier.size(16.dp),
                    tint = if (device.batteryPct <= 15 && !device.charging) Palette.red else Palette.subText,
                )
                Text("${device.batteryPct}%", color = Palette.subText, fontSize = 13.sp)
            }
        }
        Icon(
            Icons.Rounded.Home, null, modifier = Modifier.size(16.dp),
            tint = when {
                homekit.paired && homekit.running -> Palette.orange
                homekit.running -> Palette.subText
                else -> Palette.faint
            },
        )
    }
}

@Composable
private fun SummaryRow(
    lights: List<Light>,
    motion: Boolean,
    homekit: HomeKitBridge.Status,
    onAllOff: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val onCount = lights.count { it.state.on }
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 2.dp)) {
        Chip(
            icon = Icons.Rounded.Lightbulb,
            iconTint = if (onCount > 0) Palette.bulbYellow else Palette.subText,
            title = "Lights",
            value = if (onCount == 0) "All Off" else "$onCount On",
            onClick = if (onCount > 0) onAllOff else null,
        )
        Chip(
            icon = Icons.Rounded.DirectionsWalk,
            iconTint = if (motion) Palette.green else Palette.subText,
            title = "Motion",
            value = if (motion) "Detected" else "Clear",
        )
        if (!homekit.paired) {
            Chip(
                icon = Icons.Rounded.Home, iconTint = Palette.orange,
                title = "Apple Home", value = "Set Up", onClick = onOpenSettings,
            )
        }
    }
}

@Composable
private fun Chip(icon: ImageVector, iconTint: Color, title: String, value: String, onClick: (() -> Unit)? = null) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(Palette.tileOff)
            .border(0.5.dp, Palette.tileOffBorder, RoundedCornerShape(50))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Icon(icon, null, tint = iconTint, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Column {
            Text(title, color = Palette.subText, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, lineHeight = 12.sp)
            Text(value, color = Palette.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, lineHeight = 16.sp)
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        color = Palette.text,
        fontSize = 20.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(top = 14.dp, bottom = 0.dp, start = 2.dp),
    )
}

/** Apple Home style small tile: icon in a circle, name, status. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun BaseTile(
    on: Boolean,
    icon: ImageVector,
    iconColor: Color,
    title: String,
    subtitle: String,
    subtitleColor: Color? = null,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.95f else 1f, label = "press")
    val bg by animateColorAsState(if (on) Palette.tileOn else Palette.tileOff, tween(220), label = "bg")
    val circle by animateColorAsState(if (on) iconColor else Color(0x26FFFFFF), tween(220), label = "circle")
    val titleColor by animateColorAsState(if (on) Palette.textOnTile else Palette.text, tween(220), label = "t")
    Box(
        Modifier
            .fillMaxWidth()
            .height(78.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(RoundedCornerShape(22.dp))
            .background(bg)
            .border(0.5.dp, if (on) Color.Transparent else Palette.tileOffBorder, RoundedCornerShape(22.dp))
            .combinedClickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick,
                onLongClick = onLongClick,
            )
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(44.dp).clip(CircleShape).background(circle), contentAlignment = Alignment.Center) {
                Icon(icon, null, tint = if (on) Color.White else Color(0xCCFFFFFF), modifier = Modifier.size(24.dp))
            }
            Spacer(Modifier.width(11.dp))
            Column {
                Text(title, color = titleColor, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    subtitle,
                    color = subtitleColor ?: if (on) Palette.subTextOnTile else Palette.subText,
                    fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1,
                )
            }
        }
    }
}

@Composable
fun LightTile(light: Light, onClick: () -> Unit, onLongClick: () -> Unit) {
    val s = light.state
    val (subtitle, subColor) = when (light.link) {
        LinkStatus.FAILED -> "No Response" to Palette.red
        LinkStatus.CONNECTING, LinkStatus.DISCONNECTED -> (if (s.on) "On · Connecting" else "Connecting…") to null
        LinkStatus.CONNECTED, LinkStatus.IDLE -> (if (s.on) "${s.brightness}%" else "Off") to null
    }
    BaseTile(
        on = s.on,
        icon = iconFor(light.config.icon),
        iconColor = s.uiColor(light.config.supportsColor),
        title = light.config.name,
        subtitle = subtitle,
        subtitleColor = subColor,
        onClick = onClick,
        onLongClick = onLongClick,
    )
}

@Composable
private fun SceneTile(scene: Scene, active: Boolean, onClick: () -> Unit) {
    val color = scene.state?.uiColor() ?: Palette.subTextOnTile
    BaseTile(
        on = active,
        icon = sceneIcon(scene.icon),
        iconColor = color,
        title = scene.name,
        subtitle = if (active) "Active" else "Scene",
        onClick = onClick,
    )
}

@Composable
private fun EmptyLights(onAdd: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .background(Palette.tileOff)
            .clickable(onClick = onAdd)
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(56.dp).clip(CircleShape).background(Palette.orange), contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.Add, null, tint = Color.White, modifier = Modifier.size(30.dp))
        }
        Spacer(Modifier.height(12.dp))
        Text("Add your lights", color = Palette.text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        Text("Tap to scan for nearby strips", color = Palette.subText, fontSize = 14.sp)
    }
}

/** Dim clock shown between "active" and "screen off". Any touch wakes the panel. */
@Composable
fun AmbientClock(lights: List<Light>) {
    val now = rememberNow()
    val time = remember(now / 60000) { SimpleDateFormat("H:mm", Locale.getDefault()).format(Date(now)) }
    val date = remember(now / 3600000) { SimpleDateFormat("EEEE, d MMMM", Locale.getDefault()).format(Date(now)) }
    val onCount = lights.count { it.state.on }
    Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(time, color = Color(0xB3FFFFFF), fontSize = 110.sp, fontWeight = FontWeight.Thin, letterSpacing = (-3).sp)
            Text(date, color = Color(0x80FFFFFF), fontSize = 20.sp)
            Spacer(Modifier.height(18.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Rounded.Lightbulb, null, modifier = Modifier.size(18.dp),
                    tint = if (onCount > 0) Palette.bulbYellow.copy(alpha = 0.7f) else Color(0x66FFFFFF),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    if (onCount == 0) "All lights off" else "$onCount light${if (onCount > 1) "s" else ""} on",
                    color = Color(0x80FFFFFF), fontSize = 16.sp,
                )
            }
        }
    }
}
