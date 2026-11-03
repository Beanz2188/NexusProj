package com.nexuslights.panel.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.material.icons.rounded.Palette as PaletteIcon
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nexuslights.panel.data.Light
import com.nexuslights.panel.data.LinkStatus
import com.nexuslights.panel.data.LightState
import com.nexuslights.panel.protocol.ColorMath
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

private data class Preset(val hue: Float, val sat: Float)

private val PRESETS = listOf(
    Preset(25f, 0.85f), Preset(32f, 0.55f), Preset(42f, 0.18f), Preset(210f, 0.12f),
    Preset(0f, 1f), Preset(28f, 1f), Preset(52f, 1f), Preset(125f, 1f),
    Preset(180f, 1f), Preset(228f, 1f), Preset(275f, 1f), Preset(320f, 0.8f),
)

@Composable
fun LightDetail(
    light: Light,
    onClose: () -> Unit,
    onPower: (Boolean) -> Unit,
    onBrightness: (Int) -> Unit,
    onColor: (Float, Float) -> Unit,
) {
    BackHandler(onBack = onClose)
    var showWheel by remember { mutableStateOf(false) }
    val s = light.state
    val color = light.config.supportsColor

    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xEB0B0B0E))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClose),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .widthIn(max = 460.dp)
                .fillMaxWidth()
                .padding(24.dp)
                // swallow taps so they don't close the sheet
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // header
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                val circle by animateColorAsState(if (s.on) s.uiColor(color) else Color(0x26FFFFFF), label = "c")
                Box(Modifier.size(48.dp).clip(CircleShape).background(circle), contentAlignment = Alignment.Center) {
                    Icon(iconFor(light.config.icon), null, tint = Color.White, modifier = Modifier.size(26.dp))
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(light.config.name, color = Palette.text, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    Text(
                        when {
                            light.link == LinkStatus.FAILED -> "No Response"
                            s.on -> "On · ${s.brightness}%"
                            else -> "Off"
                        },
                        color = if (light.link == LinkStatus.FAILED) Palette.red else Palette.subText, fontSize = 15.sp,
                    )
                }
                RoundButton(Icons.Rounded.Close, onClick = onClose)
            }

            Spacer(Modifier.height(30.dp))

            AnimatedContent(showWheel, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "mode") { wheel ->
                Box(Modifier.height(360.dp).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    if (wheel && color) ColorWheel(s.hue, s.saturation, onColor)
                    else BrightnessPill(s, color, onBrightness, onPower)
                }
            }

            Spacer(Modifier.height(26.dp))

            if (color) Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                PRESETS.forEach { p ->
                    val (r, g, b) = ColorMath.hsvToRgb(p.hue, p.sat, 1f)
                    val selected = kotlin.math.abs(p.hue - s.hue) < 3 && kotlin.math.abs(p.sat - s.saturation) < 0.04f
                    Box(
                        Modifier
                            .size(46.dp)
                            .border(2.5.dp, if (selected) Color.White else Color.Transparent, CircleShape)
                            .padding(4.dp)
                            .clip(CircleShape)
                            .background(Color(r, g, b))
                            .clickable { onColor(p.hue, p.sat) }
                    )
                }
            }

            Spacer(Modifier.height(24.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                PillButton(
                    icon = Icons.Rounded.PowerSettingsNew,
                    label = if (s.on) "Turn Off" else "Turn On",
                    highlighted = s.on,
                    onClick = { onPower(!s.on) },
                )
                if (color) PillButton(
                    icon = if (showWheel) Icons.Rounded.Tune else Icons.Rounded.PaletteIcon,
                    label = if (showWheel) "Brightness" else "Color",
                    highlighted = false,
                    onClick = { showWheel = !showWheel },
                )
            }
        }
    }
}

/** Apple Home style vertical brightness control. Drag or tap anywhere on it. */
@Composable
private fun BrightnessPill(s: LightState, color: Boolean, onBrightness: (Int) -> Unit, onPower: (Boolean) -> Unit) {
    var level by remember { mutableFloatStateOf(if (s.on) s.brightness / 100f else 0f) }
    var dragging by remember { mutableStateOf(false) }
    LaunchedEffect(s.on, s.brightness) { if (!dragging) level = if (s.on) s.brightness / 100f else 0f }
    val fill = lerp(Color.White, s.uiColor(color), 0.35f)

    fun commit(v: Float) {
        level = v.coerceIn(0f, 1f)
        val pct = (level * 100).roundToInt()
        if (pct <= 0) onPower(false) else onBrightness(pct)
    }

    Box(
        Modifier
            .width(140.dp)
            .fillMaxHeight()
            .clip(RoundedCornerShape(42.dp))
            .background(Color(0x40767680))
            .pointerInput(Unit) {
                detectTapGestures { o -> commit(1f - o.y / size.height) }
            }
            .pointerInput(Unit) {
                detectVerticalDragGestures(
                    onDragStart = { dragging = true },
                    onDragEnd = { dragging = false },
                    onDragCancel = { dragging = false },
                ) { change, dy ->
                    change.consume()
                    commit(level - dy / size.height)
                }
            },
        contentAlignment = Alignment.BottomCenter,
    ) {
        Box(Modifier.fillMaxWidth().fillMaxHeight(level.coerceIn(0f, 1f)).background(fill))
        Icon(
            Icons.Rounded.LightMode, null,
            tint = if (level > 0.12f) Color(0xFF8E8E93) else Color.White,
            modifier = Modifier.padding(bottom = 22.dp).size(30.dp),
        )
        Text(
            if (level <= 0f) "Off" else "${(level * 100).roundToInt()}%",
            color = if (level > 0.9f) Palette.textOnTile else Palette.text,
            fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 18.dp),
        )
    }
}

/** Hue around the circle, saturation from the center out. */
@Composable
private fun ColorWheel(hue: Float, sat: Float, onColor: (Float, Float) -> Unit) {
    var h by remember { mutableFloatStateOf(hue) }
    var sa by remember { mutableFloatStateOf(sat) }
    LaunchedEffect(hue, sat) { h = hue; sa = sat }
    val hues = remember { (0..6).map { i -> val (r, g, b) = ColorMath.hsvToRgb(i * 60f, 1f, 1f); Color(r, g, b) } }

    fun pick(o: Offset, w: Float, hgt: Float) {
        val cx = w / 2; val cy = hgt / 2
        val r = min(cx, cy)
        val dx = o.x - cx; val dy = o.y - cy
        val angle = Math.toDegrees(atan2(dy, dx).toDouble()).toFloat()
        h = (angle + 360f) % 360f
        sa = (sqrt(dx * dx + dy * dy) / r).coerceIn(0f, 1f)
        onColor(h, sa)
    }

    Canvas(
        Modifier
            .size(320.dp)
            .pointerInput(Unit) { detectTapGestures { pick(it, size.width.toFloat(), size.height.toFloat()) } }
            .pointerInput(Unit) {
                detectDragGestures { change, _ ->
                    change.consume(); pick(change.position, size.width.toFloat(), size.height.toFloat())
                }
            }
    ) {
        val r = size.minDimension / 2
        drawCircle(Brush.sweepGradient(hues, center), r)
        drawCircle(Brush.radialGradient(listOf(Color.White, Color.White.copy(alpha = 0f)), center, r), r)
        val rad = Math.toRadians(h.toDouble())
        val knob = Offset(center.x + (cos(rad) * sa * r).toFloat(), center.y + (sin(rad) * sa * r).toFloat())
        val (cr, cg, cb) = ColorMath.hsvToRgb(h, sa, 1f)
        drawCircle(Color(cr, cg, cb), 20.dp.toPx(), knob)
        drawCircle(Color.White, 20.dp.toPx(), knob, style = Stroke(4.dp.toPx()))
        drawCircle(Color(0x33000000), 22.dp.toPx(), knob, style = Stroke(1.dp.toPx()))
    }
}

@Composable
fun RoundButton(icon: ImageVector, onClick: () -> Unit) {
    Box(
        Modifier.size(38.dp).clip(CircleShape).background(Color(0x40767680)).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, null, tint = Palette.subText, modifier = Modifier.size(20.dp)) }
}

@Composable
private fun PillButton(icon: ImageVector, label: String, highlighted: Boolean, onClick: () -> Unit) {
    val bg by animateColorAsState(if (highlighted) Palette.tileOn else Color(0x40767680), label = "pb")
    val fg = if (highlighted) Palette.textOnTile else Palette.text
    Row(
        Modifier.clip(RoundedCornerShape(50)).background(bg).clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = if (highlighted) Palette.orange else fg, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Text(label, color = fg, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
    }
}
