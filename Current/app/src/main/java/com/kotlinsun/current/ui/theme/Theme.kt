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
    secondary = BrowserAccent,
    onSecondary = BrowserInk,
    secondaryContainer = BrowserAccentSoft,
    onSecondaryContainer = BrowserInk,
    tertiary = Color(0xFF0B69EB),
    onTertiary = Color.White,
    background = BrowserBackground,
    onBackground = BrowserInk,
    surface = BrowserSurface,
    onSurface = BrowserInk,
    surfaceVariant = BrowserSoftGray,
    onSurfaceVariant = BrowserMuted,
    surfaceTint = BrowserInk,
    inverseSurface = BrowserInk,
    inverseOnSurface = Color.White,
    error = Color(0xFFED4E4E),
    onError = Color.White,
    errorContainer = Color(0xFFFFF5F5),
    onErrorContainer = BrowserInk,
    outline = BrowserOutline,
    outlineVariant = BrowserOutlineLite,
    scrim = Color.Black,
    surfaceBright = Color.White,
    surfaceDim = BrowserSoftGray,
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = BrowserBackground,
    surfaceContainer = BrowserSoftGray,
    surfaceContainerHigh = BrowserSoftGray,
    surfaceContainerHighest = Color(0xFFEAEAEA),
)

private val DarkColorScheme = darkColorScheme(
    primary = BrowserDarkText,
    onPrimary = BrowserDarkBackground,
    primaryContainer = BrowserDarkOutline,
    onPrimaryContainer = BrowserDarkText,
    secondary = BrowserAccent,
    onSecondary = BrowserInk,
    secondaryContainer = BrowserDarkAccentSoft,
    onSecondaryContainer = BrowserAccent,
    tertiary = Color(0xFF418AF0),
    onTertiary = BrowserInk,
    background = BrowserDarkBackground,
    onBackground = BrowserDarkText,
    surface = BrowserDarkSurface,
    onSurface = BrowserDarkText,
    surfaceVariant = BrowserDarkRaised,
    onSurfaceVariant = BrowserDarkMuted,
    surfaceTint = BrowserDarkText,
    inverseSurface = BrowserDarkText,
    inverseOnSurface = BrowserInk,
    error = Color(0xFFEB3838),
    onError = Color.White,
    errorContainer = Color(0xFF521D1D),
    onErrorContainer = BrowserDarkText,
    outline = BrowserDarkOutline,
    outlineVariant = BrowserDarkRaised,
    scrim = Color.Black,
    surfaceBright = BrowserDarkOutline,
    surfaceDim = BrowserDarkBackground,
    surfaceContainerLowest = BrowserDarkBackground,
    surfaceContainerLow = BrowserDarkSurface,
    surfaceContainer = BrowserDarkRaised,
    surfaceContainerHigh = BrowserDarkOutline,
    surfaceContainerHighest = BrowserDarkOutline,
)

// 아이콘 8, 칩 12, 버튼·입력 16, 카드 20, 대화상자 24dp.
private val BrowserShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(24.dp),
)

@Composable
fun CurrentTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme,
        shapes = BrowserShapes,
        typography = Typography,
        content = content,
    )
}
