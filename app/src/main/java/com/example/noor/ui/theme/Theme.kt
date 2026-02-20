package com.example.noor.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColorScheme = darkColorScheme(
    primary = NoorRed,
    secondary = NoorGreen,
    tertiary = NoorGreenContainer,
    background = DarkBg,
    surface = DarkSurface,
    onPrimary = PureWhite,
    onSecondary = PureWhite,
    onTertiary = DarkBg,
    onBackground = PureWhite,
    onSurface = PureWhite,
    primaryContainer = Color(0xFF5A2A20),
    secondaryContainer = Color(0xFF1F5A3A),
    surfaceVariant = DarkCardBg,
    onSurfaceVariant = Color(0xFFCBD5E1)
)

private val LightColorScheme = lightColorScheme(
    primary = NoorRed,
    secondary = NoorGreen,
    tertiary = NoorGreen,
    background = LightBg,
    surface = LightSurface,
    onPrimary = PureWhite,
    onSecondary = PureWhite,
    onTertiary = PureWhite,
    onBackground = DarkGrey,
    onSurface = DarkGrey,
    primaryContainer = NoorRedContainer,
    secondaryContainer = NoorGreenContainer
)

@Composable
fun NoorTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
