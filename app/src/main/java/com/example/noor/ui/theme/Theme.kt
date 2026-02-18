package com.example.noor.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColorScheme = darkColorScheme(
    primary = DeepRed,
    secondary = DeepGreen,
    tertiary = MintGreen,
    background = DarkBg,
    surface = DarkSurface,
    onPrimary = PureWhite,
    onSecondary = PureWhite,
    onTertiary = DarkBg,
    onBackground = PureWhite,
    onSurface = PureWhite,
    primaryContainer = Color(0xFFFF6B6B),
    secondaryContainer = Color(0xFF34D399),
    surfaceVariant = DarkCardBg,
    onSurfaceVariant = Color(0xFFCBD5E1)
)

private val LightColorScheme = lightColorScheme(
    primary = DeepRed,
    secondary = ForestGreen,
    tertiary = DeepGreen,
    background = PureWhite,
    surface = PureWhite,
    onPrimary = PureWhite,
    onSecondary = PureWhite,
    onTertiary = PureWhite,
    onBackground = DarkGrey,
    onSurface = DarkGrey,
    primaryContainer = LightRed,
    secondaryContainer = MintGreen
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
