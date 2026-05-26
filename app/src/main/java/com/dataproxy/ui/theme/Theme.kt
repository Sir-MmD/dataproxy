package com.dataproxy.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

@Composable
fun DataProxyTheme(
    themeMode: ThemeMode = ThemeMode.System,
    content: @Composable () -> Unit,
) {
    val isDark = when (themeMode) {
        ThemeMode.Dark -> true
        ThemeMode.Light -> false
        ThemeMode.System -> isSystemInDarkTheme()
    }
    val dpColors = if (isDark) DarkDPColors else LightDPColors
    val colorScheme = if (isDark) {
        darkColorScheme(
            primary = Accent,
            onPrimary = Color.Black,
            primaryContainer = AccentDim,
            onPrimaryContainer = dpColors.textPrimary,

            secondary = Info,
            onSecondary = Color.Black,

            background = dpColors.ink,
            onBackground = dpColors.textPrimary,

            surface = dpColors.ink,
            onSurface = dpColors.textPrimary,

            surfaceVariant = dpColors.surfaceMid,
            onSurfaceVariant = dpColors.textSecondary,

            surfaceContainerLowest = dpColors.ink,
            surfaceContainerLow = dpColors.surfaceLow,
            surfaceContainer = dpColors.surfaceMid,
            surfaceContainerHigh = dpColors.surfaceHigh,
            surfaceContainerHighest = Color(0xFF1F2026),

            outline = dpColors.outlineStrong,
            outlineVariant = dpColors.outlineSoft,

            error = Danger,
            onError = Color.Black,
        )
    } else {
        lightColorScheme(
            primary = Accent,
            onPrimary = Color.Black,
            primaryContainer = AccentDim,
            onPrimaryContainer = dpColors.textPrimary,

            secondary = Info,
            onSecondary = Color.Black,

            background = dpColors.ink,
            onBackground = dpColors.textPrimary,

            surface = dpColors.ink,
            onSurface = dpColors.textPrimary,

            surfaceVariant = dpColors.surfaceMid,
            onSurfaceVariant = dpColors.textSecondary,

            surfaceContainerLowest = dpColors.ink,
            surfaceContainerLow = dpColors.surfaceLow,
            surfaceContainer = dpColors.surfaceMid,
            surfaceContainerHigh = dpColors.surfaceHigh,
            surfaceContainerHighest = Color(0xFFDDDEE3),

            outline = dpColors.outlineStrong,
            outlineVariant = dpColors.outlineSoft,

            error = Danger,
            onError = Color.Black,
        )
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = Color.Transparent.toArgb()
            window.navigationBarColor = Color.Transparent.toArgb()
            WindowCompat.setDecorFitsSystemWindows(window, false)
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !isDark
                isAppearanceLightNavigationBars = !isDark
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                @Suppress("DEPRECATION")
                window.isNavigationBarContrastEnforced = false
            }
        }
    }
    CompositionLocalProvider(LocalDPColors provides dpColors) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = DataProxyTypography,
            content = content,
        )
    }
}
