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
    primary = BrowserInk,
    onPrimary = Color.White,
    primaryContainer = BrowserSoftGray,
    onPrimaryContainer = BrowserInk,
    secondary = BrowserInk,
    onSecondary = Color.White,
    secondaryContainer = BrowserSoftGray,
    onSecondaryContainer = BrowserInk,
    tertiary = BrowserInk,
    onTertiary = Color.White,
    tertiaryContainer = BrowserSoftGray,
    onTertiaryContainer = BrowserInk,
    background = BrowserBackground,
    onBackground = BrowserInk,
    surface = BrowserSurface,
    onSurface = BrowserInk,
    surfaceVariant = BrowserSoftGray,
    onSurfaceVariant = BrowserMuted,
    surfaceTint = BrowserInk,
    inverseSurface = BrowserInk,
    inverseOnSurface = Color.White,
    error = BrowserInk,
    onError = Color.White,
    errorContainer = BrowserSoftGray,
    onErrorContainer = BrowserInk,
    outline = BrowserOutline,
    outlineVariant = BrowserOutline,
    scrim = Color.Black,
    surfaceBright = Color.White,
    surfaceContainer = Color(0xFFEFEFEF),
    surfaceContainerHigh = Color(0xFFE8E8E8),
    surfaceContainerHighest = Color(0xFFE0E0E0),
    surfaceContainerLow = Color(0xFFF4F4F4),
    surfaceContainerLowest = Color.White,
    surfaceDim = Color(0xFFDEDEDE),
    primaryFixed = BrowserSoftGray,
    primaryFixedDim = Color(0xFFCECECE),
    onPrimaryFixed = BrowserInk,
    onPrimaryFixedVariant = BrowserMuted,
    secondaryFixed = BrowserSoftGray,
    secondaryFixedDim = Color(0xFFCECECE),
    onSecondaryFixed = BrowserInk,
    onSecondaryFixedVariant = BrowserMuted,
    tertiaryFixed = BrowserSoftGray,
    tertiaryFixedDim = Color(0xFFCECECE),
    onTertiaryFixed = BrowserInk,
    onTertiaryFixedVariant = BrowserMuted,
    inversePrimary = Color.White,
)

private val DarkColorScheme = darkColorScheme(
    primary = BrowserDarkText,
    onPrimary = BrowserDarkBackground,
    primaryContainer = BrowserDarkRaised,
    onPrimaryContainer = BrowserDarkText,
    secondary = BrowserDarkText,
    onSecondary = BrowserDarkBackground,
    secondaryContainer = BrowserDarkRaised,
    onSecondaryContainer = BrowserDarkText,
    tertiary = BrowserDarkText,
    onTertiary = BrowserDarkBackground,
    tertiaryContainer = BrowserDarkRaised,
    onTertiaryContainer = BrowserDarkText,
    background = BrowserDarkBackground,
    onBackground = BrowserDarkText,
    surface = BrowserDarkSurface,
    onSurface = BrowserDarkText,
    surfaceVariant = BrowserDarkRaised,
    onSurfaceVariant = BrowserDarkMuted,
    surfaceTint = BrowserDarkText,
    inverseSurface = BrowserDarkText,
    inverseOnSurface = BrowserDarkBackground,
    error = BrowserDarkText,
    onError = BrowserDarkBackground,
    errorContainer = BrowserDarkRaised,
    onErrorContainer = BrowserDarkText,
    outline = BrowserDarkOutline,
    outlineVariant = BrowserDarkOutline,
    scrim = Color.Black,
    surfaceBright = Color(0xFF333333),
    surfaceContainer = Color(0xFF222222),
    surfaceContainerHigh = Color(0xFF292929),
    surfaceContainerHighest = Color(0xFF333333),
    surfaceContainerLow = Color(0xFF1D1D1D),
    surfaceContainerLowest = Color(0xFF101010),
    surfaceDim = Color(0xFF111111),
    primaryFixed = BrowserSoftGray,
    primaryFixedDim = Color(0xFFCECECE),
    onPrimaryFixed = BrowserInk,
    onPrimaryFixedVariant = BrowserMuted,
    secondaryFixed = BrowserSoftGray,
    secondaryFixedDim = Color(0xFFCECECE),
    onSecondaryFixed = BrowserInk,
    onSecondaryFixedVariant = BrowserMuted,
    tertiaryFixed = BrowserSoftGray,
    tertiaryFixedDim = Color(0xFFCECECE),
    onTertiaryFixed = BrowserInk,
    onTertiaryFixedVariant = BrowserMuted,
    inversePrimary = BrowserInk,
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
