package com.kaoyan.wordhelper.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val LightColorScheme = lightColorScheme(
    primary = AcademicBlue,
    onPrimary = CardWhite,
    primaryContainer = BlueContainer,
    onPrimaryContainer = AcademicBlueDeep,
    secondary = AccentBlue,
    onSecondary = CardWhite,
    secondaryContainer = BlueContainer,
    tertiary = KnownGreen,
    onTertiary = CardWhite,
    tertiaryContainer = PositiveContainer,
    background = LightBackground,
    surface = CardWhite,
    surfaceVariant = SurfaceSoft,
    onBackground = TextPrimary,
    onSurface = TextPrimary,
    onSurfaceVariant = TextSecondary,
    outline = OutlineSoft,
    error = AlertRed,
    onError = CardWhite
)

private val DarkColorScheme = darkColorScheme(
    primary = SoftBlue,
    onPrimary = DarkBackground,
    primaryContainer = DarkSurfaceSoft,
    onPrimaryContainer = DarkTextPrimary,
    secondary = SoftBlue,
    onSecondary = DarkBackground,
    secondaryContainer = DarkSurfaceSoft,
    tertiary = KnownGreen,
    onTertiary = DarkBackground,
    tertiaryContainer = DarkSurfaceSoft,
    background = DarkBackground,
    surface = DarkCard,
    surfaceVariant = DarkSurfaceSoft,
    onBackground = DarkTextPrimary,
    onSurface = DarkTextPrimary,
    onSurfaceVariant = DarkTextSecondary,
    outline = OutlineSoft,
    error = AlertRed,
    onError = DarkBackground
)

/**
 * App 真实明暗信号：KaoyanWordTheme 里写入，供卡片等「自带配色系统」的组件读取。
 * 不能用 MaterialTheme.colorScheme.background 判断——HoloCardTheme 会把 background
 * 覆盖成半透明白面板，内层再读就会把深色皮肤的卡片误判成亮色。
 */
val LocalAppDarkTheme = staticCompositionLocalOf { false }

@Composable
fun KaoyanWordTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    fontScale: Float = 1.0f,
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
        }
    }

    CompositionLocalProvider(LocalAppDarkTheme provides darkTheme) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = appTypography(fontScale),
            content = content
        )
    }
}
