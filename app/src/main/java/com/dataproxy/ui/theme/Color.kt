package com.dataproxy.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

// ---------------------------------------------------------------- Dark palette
// True OLED black, saves real power on AMOLED panels.
private val DarkInk = Color(0xFF000000)
private val DarkSurfaceLow = Color(0xFF0A0A0A)
private val DarkSurfaceMid = Color(0xFF121214)
private val DarkSurfaceHigh = Color(0xFF18181B)
private val DarkOutlineSoft = Color(0xFF22232A)
private val DarkOutlineStrong = Color(0xFF2E2F38)
private val DarkTextPrimary = Color(0xFFF5F5F7)
private val DarkTextSecondary = Color(0xFFA0A0A8)
private val DarkTextMuted = Color(0xFF60606A)

// --------------------------------------------------------------- Light palette
private val LightInk = Color(0xFFFAFAFB)
private val LightSurfaceLow = Color(0xFFFFFFFF)
private val LightSurfaceMid = Color(0xFFF1F2F5)
private val LightSurfaceHigh = Color(0xFFE7E8ED)
private val LightOutlineSoft = Color(0xFFE2E3E8)
private val LightOutlineStrong = Color(0xFFC8CAD1)
private val LightTextPrimary = Color(0xFF0B0B10)
private val LightTextSecondary = Color(0xFF4A4B55)
private val LightTextMuted = Color(0xFF7C7E88)

// ---------------------------------------------------- Theme-independent accents
// Mint-green accent, signals "live" without feeling neon.
val Accent = Color(0xFF3DDC97)
val AccentDim = Color(0xFF1B6E51)
val AccentGlow = Color(0x333DDC97)

// State colours are saturated enough to work on both light and dark backgrounds.
val Danger = Color(0xFFFF6B6B)
val Warning = Color(0xFFFFB454)
val Info = Color(0xFF6BCBFF)

@Immutable
data class DPColors(
    val ink: Color,
    val surfaceLow: Color,
    val surfaceMid: Color,
    val surfaceHigh: Color,
    val outlineSoft: Color,
    val outlineStrong: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textMuted: Color,
)

internal val DarkDPColors = DPColors(
    ink = DarkInk,
    surfaceLow = DarkSurfaceLow,
    surfaceMid = DarkSurfaceMid,
    surfaceHigh = DarkSurfaceHigh,
    outlineSoft = DarkOutlineSoft,
    outlineStrong = DarkOutlineStrong,
    textPrimary = DarkTextPrimary,
    textSecondary = DarkTextSecondary,
    textMuted = DarkTextMuted,
)

internal val LightDPColors = DPColors(
    ink = LightInk,
    surfaceLow = LightSurfaceLow,
    surfaceMid = LightSurfaceMid,
    surfaceHigh = LightSurfaceHigh,
    outlineSoft = LightOutlineSoft,
    outlineStrong = LightOutlineStrong,
    textPrimary = LightTextPrimary,
    textSecondary = LightTextSecondary,
    textMuted = LightTextMuted,
)

val LocalDPColors = staticCompositionLocalOf { DarkDPColors }

// @Composable getters keep call-site syntax `Modifier.background(Ink)` working
// while letting the value swap at runtime when the theme changes. For non-
// composable scopes (Canvas lambdas etc.) capture the value as a local val
// inside the @Composable scope first.
val Ink: Color @Composable @ReadOnlyComposable get() = LocalDPColors.current.ink
val SurfaceLow: Color @Composable @ReadOnlyComposable get() = LocalDPColors.current.surfaceLow
val SurfaceMid: Color @Composable @ReadOnlyComposable get() = LocalDPColors.current.surfaceMid
val SurfaceHigh: Color @Composable @ReadOnlyComposable get() = LocalDPColors.current.surfaceHigh
val OutlineSoft: Color @Composable @ReadOnlyComposable get() = LocalDPColors.current.outlineSoft
val OutlineStrong: Color @Composable @ReadOnlyComposable get() = LocalDPColors.current.outlineStrong
val TextPrimary: Color @Composable @ReadOnlyComposable get() = LocalDPColors.current.textPrimary
val TextSecondary: Color @Composable @ReadOnlyComposable get() = LocalDPColors.current.textSecondary
val TextMuted: Color @Composable @ReadOnlyComposable get() = LocalDPColors.current.textMuted
