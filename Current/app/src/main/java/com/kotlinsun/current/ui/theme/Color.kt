package com.kotlinsun.current.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable

// Current adapts the reference's neutral ramp and restrained orange accent.
val BrowserBackground = Color(0xFFFAFAFA)
val BrowserSurface = Color(0xFFFFFFFF)
val BrowserInk = Color(0xFF000000)
val BrowserMuted = Color(0xFF424242)
val BrowserPlaceholder = Color(0xFF808080)
val BrowserSoftGray = Color(0xFFF2F2F2)
val BrowserOutline = Color(0xFFD4D4D4)
val BrowserOutlineLite = Color(0xFFEBEBEB)
val BrowserNavigation = BrowserInk
val BrowserAccent = Color(0xFFFF6A0D)
val BrowserAccentSoft = Color(0xFFFFF4ED)

val BrowserDarkBackground = Color(0xFF000000)
val BrowserDarkSurface = Color(0xFF1A1A1A)
val BrowserDarkRaised = Color(0xFF2E2E2E)
val BrowserDarkText = Color(0xFFFFFFFF)
val BrowserDarkMuted = Color(0xFFD4D4D4)
val BrowserDarkOutline = Color(0xFF424242)
val BrowserDarkAccentSoft = Color(0xFF331D0F)

@Composable
fun currentAccentTextColor(): Color = if (MaterialTheme.colorScheme.background.luminance() < 0.5f)
    BrowserAccent else Color(0xFFAD4700)

@Composable
fun currentPlaceholderColor(): Color = if (MaterialTheme.colorScheme.background.luminance() < 0.5f)
    Color(0xFFAAAAAA) else BrowserPlaceholder

@Composable
fun currentSuccessColor(): Color = if (MaterialTheme.colorScheme.background.luminance() < 0.5f)
    Color(0xFF21C798) else Color(0xFF13745A)

@Composable
fun currentWarningColor(): Color = if (MaterialTheme.colorScheme.background.luminance() < 0.5f)
    Color(0xFFD1AD36) else Color(0xFF926000)

@Composable
fun currentErrorTextColor(): Color = if (MaterialTheme.colorScheme.background.luminance() < 0.5f)
    MaterialTheme.colorScheme.error else Color(0xFFBA2525)
