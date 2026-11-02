package com.cj.turboboost.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp

/** Every colour and type token the screens use. Nothing else in the UI defines a literal colour. */
object Tac {
    // Background gradient, top to bottom.
    val BgTop = Color(0xFF0B1118)
    val BgBottom = Color(0xFF10202B)
    /** Flat fallback where a gradient is not drawn (dialog scrims, status bar). */
    val Bg = BgTop
    val BgBrush: Brush get() = Brush.verticalGradient(listOf(BgTop, BgBottom))

    val Surface = Color(0xFF141C24)
    val SurfaceElevated = Color(0xFF1B2631)
    val Line = Color(0xFF263341)

    val Accent = Color(0xFF7FD6FF)
    val Ok = Color(0xFF4ADE9A)
    val Warn = Color(0xFFFFC76B)
    val Danger = Color(0xFFFF6B7A)

    val TextHi = Color(0xFFE6EEF5)
    val TextLo = Color(0xFF8A9BAA)

    // Names the screens already use.
    val Cyan = Accent
    val Amber = Warn
    val Green = Ok
    val Red = Danger
}

/** The system font, everywhere. */
val UiFont: FontFamily = FontFamily.Default

/** Letter spacing of the small uppercase labels. */
val LabelTracking = 2.sp

@Composable
fun TurboTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Tac.Accent,
            secondary = Tac.Warn,
            background = Tac.Bg,
            surface = Tac.Surface,
            onBackground = Tac.TextHi,
            onSurface = Tac.TextHi
        ),
        content = content
    )
}
