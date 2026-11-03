package com.nexuslights.panel.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Bed
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.CenterFocusStrong
import androidx.compose.material.icons.rounded.Computer
import androidx.compose.material.icons.rounded.EmojiObjects
import androidx.compose.material.icons.rounded.Lightbulb
import androidx.compose.material.icons.rounded.LinearScale
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.material.icons.rounded.WbIncandescent
import androidx.compose.material.icons.rounded.WbSunny
import androidx.compose.material.icons.rounded.Weekend
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import com.nexuslights.panel.data.LightIcon
import com.nexuslights.panel.data.LightState

/** Palette modeled on Apple Home's dark appearance. */
object Palette {
    val bgTop = Color(0xFF23232B)
    val bgBottom = Color(0xFF0A0A0D)
    val tileOff = Color(0x6B2C2C31)
    val tileOffBorder = Color(0x14FFFFFF)
    val tileOn = Color(0xF7F5F5F7)
    val textOnTile = Color(0xFF1C1C1E)
    val subTextOnTile = Color(0xFF6E6E73)
    val text = Color(0xFFFFFFFF)
    val subText = Color(0x99EBEBF5)
    val faint = Color(0x4DEBEBF5)
    val card = Color(0xFF1C1C1E)
    val cardRow = Color(0xFF2C2C2E)
    val separator = Color(0x33545458)
    val orange = Color(0xFFFF9F0A)
    val yellow = Color(0xFFFFD60A)
    val green = Color(0xFF30D158)
    val red = Color(0xFFFF453A)
    val blue = Color(0xFF0A84FF)
    val bulbYellow = Color(0xFFFFC300)
}

@Composable
fun PanelTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Palette.orange,
            onPrimary = Color.Black,
            secondary = Palette.orange,
            background = Color.Black,
            surface = Palette.card,
            surfaceVariant = Palette.cardRow,
            surfaceContainerHigh = Palette.card,
            onSurface = Palette.text,
            onSurfaceVariant = Palette.subText,
            outline = Palette.separator,
        ),
        content = content,
    )
}

fun iconFor(icon: String): ImageVector = when (LightIcon.of(icon)) {
    LightIcon.STRIP -> Icons.Rounded.LinearScale
    LightIcon.BULB -> Icons.Rounded.Lightbulb
    LightIcon.LAMP -> Icons.Rounded.EmojiObjects
    LightIcon.CEILING -> Icons.Rounded.WbIncandescent
    LightIcon.TV -> Icons.Rounded.Tv
    LightIcon.BED -> Icons.Rounded.Bed
    LightIcon.DESK -> Icons.Rounded.Computer
}

fun modeIcon(name: String): ImageVector {
    val n = name.lowercase()
    return when {
        "tv" in n || "ambi" in n -> Icons.Rounded.Tv
        "music" in n -> Icons.Rounded.MusicNote
        "rainbow" in n -> Icons.Rounded.AutoAwesome
        else -> Icons.Rounded.AutoAwesome
    }
}

fun sceneIcon(id: String): ImageVector = when (id) {
    "power" -> Icons.Rounded.PowerSettingsNew
    "sun" -> Icons.Rounded.WbSunny
    "sofa" -> Icons.Rounded.Weekend
    "focus" -> Icons.Rounded.CenterFocusStrong
    "movie" -> Icons.Rounded.Movie
    "moon" -> Icons.Rounded.Bedtime
    else -> Icons.Rounded.Lightbulb
}

/** Color to draw a light with: its own color, or Apple's bulb yellow for "white" light. */
fun LightState.uiColor(supportsColor: Boolean = true): Color {
    if (!supportsColor || saturation < 0.22f) return Palette.bulbYellow
    val (r, g, b) = displayRgb()
    return Color(r, g, b)
}

fun lerp(a: Color, b: Color, t: Float) = Color(
    a.red + (b.red - a.red) * t,
    a.green + (b.green - a.green) * t,
    a.blue + (b.blue - a.blue) * t,
    a.alpha + (b.alpha - a.alpha) * t,
)
