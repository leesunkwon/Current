package com.kotlinsun.current.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

private val LightColorScheme = lightColorScheme(
    primary = BrowserViolet,
    onPrimary = Color.White,
    primaryContainer = BrowserSoftViolet,
    onPrimaryContainer = BrowserInk,
    secondary = BrowserInk,
    onSecondary = Color.White,
    background = BrowserBackground,
    onBackground = BrowserInk,
    surface = BrowserSurface,
    onSurface = BrowserInk,
    surfaceVariant = BrowserSoftViolet,
    onSurfaceVariant = BrowserMuted,
    outline = BrowserOutline,
    outlineVariant = BrowserOutline,
)

private val DarkColorScheme = darkColorScheme(
    primary = BrowserDarkViolet,
    onPrimary = BrowserDarkBackground,
    primaryContainer = BrowserDarkRaised,
    onPrimaryContainer = BrowserDarkText,
    secondary = BrowserDarkText,
    onSecondary = BrowserDarkBackground,
    background = BrowserDarkBackground,
    onBackground = BrowserDarkText,
    surface = BrowserDarkSurface,
    onSurface = BrowserDarkText,
    surfaceVariant = BrowserDarkRaised,
    onSurfaceVariant = BrowserDarkMuted,
    outline = BrowserDarkOutline,
    outlineVariant = BrowserDarkOutline,
)

private val BrowserShapes = Shapes(
    small = RoundedCornerShape(14.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(28.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

@Composable
fun CurrentTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme,
        shapes = BrowserShapes,
        typography = Typography,
        content = content
    )
}
