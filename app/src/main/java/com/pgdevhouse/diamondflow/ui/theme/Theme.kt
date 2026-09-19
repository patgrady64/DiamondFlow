package com.pgdevhouse.diamondflow.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColorScheme = darkColorScheme(
    primary = DiamondGreen,
    onPrimary = DiamondOnDark,
    primaryContainer = DiamondForestSoft,
    onPrimaryContainer = DiamondOnDark,
    secondary = DiamondViolet,
    onSecondary = DiamondOnDark,
    tertiary = DiamondBrown,
    onTertiary = DiamondOnDark,
    background = DiamondForest,
    onBackground = DiamondOnDark,
    surface = DiamondForest,
    onSurface = DiamondOnDark,
    surfaceVariant = DiamondForestSoft,
    onSurfaceVariant = DiamondCreamVariant,
    outline = DiamondBrown
)

private val LightColorScheme = lightColorScheme(
    primary = DiamondGreen,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD7ECD4),
    onPrimaryContainer = DiamondForest,
    secondary = DiamondViolet,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE4E0FF),
    onSecondaryContainer = DiamondViolet,
    tertiary = DiamondBrown,
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFF2DCCB),
    onTertiaryContainer = DiamondForest,
    background = DiamondCream,
    onBackground = DiamondForest,
    surface = DiamondCreamSurface,
    onSurface = DiamondForest,
    surfaceVariant = DiamondCreamVariant,
    onSurfaceVariant = DiamondForestSoft,
    outline = DiamondBrown
)

@Composable
fun InningTrackTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme,
        typography = Typography,
        content = content
    )
}
