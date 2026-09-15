package com.synclisten.app.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val AuroraDarkColors = darkColorScheme(
    primary = Violet60,
    onPrimary = Bg950,
    primaryContainer = Violet20,
    onPrimaryContainer = Violet80,
    secondary = Orange60,
    onSecondary = Bg950,
    secondaryContainer = Orange20,
    onSecondaryContainer = Orange80,
    tertiary = Teal60,
    onTertiary = Bg950,
    background = Bg950,
    onBackground = TextPrimary,
    surface = Bg900,
    onSurface = TextPrimary,
    surfaceVariant = Bg800,
    onSurfaceVariant = TextSecondary,
    outline = Bg700,
    error = ErrorRed,
    onError = Color.White,
)

private val AuroraLightColors = lightColorScheme(
    primary = Violet40,
    onPrimary = Color.White,
    primaryContainer = Violet80,
    onPrimaryContainer = Violet20,
    secondary = Orange40,
    onSecondary = Color.White,
    secondaryContainer = Orange80,
    onSecondaryContainer = Orange20,
    tertiary = Teal40,
    onTertiary = Color.White,
    background = Color(0xFFFAFAFA),
    onBackground = Color(0xFF18181B),
    surface = Color.White,
    onSurface = Color(0xFF18181B),
    surfaceVariant = Color(0xFFF4F4F5),
    onSurfaceVariant = Color(0xFF71717A),
    outline = Color(0xFFE4E4E7),
    error = ErrorRed,
    onError = Color.White,
)

@Composable
fun SyncListenTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colorScheme = if (darkTheme) AuroraDarkColors else AuroraLightColors

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = Color.Transparent.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = AuroraTypography,
        content = content,
    )
}
