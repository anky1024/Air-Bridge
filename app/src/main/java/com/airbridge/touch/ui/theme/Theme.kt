package com.airbridge.touch.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Indigo = Color(0xFF3F51B5)
private val IndigoLight = Color(0xFF7986CB)
private val Teal = Color(0xFF00897B)
private val DarkSurface = Color(0xFF121212)
private val DarkSurfaceVariant = Color(0xFF1E1E1E)

private val DarkColors = darkColorScheme(
    primary = IndigoLight,
    secondary = Teal,
    surface = DarkSurface,
    surfaceVariant = DarkSurfaceVariant
)

private val LightColors = lightColorScheme(
    primary = Indigo,
    secondary = Teal
)

@Composable
fun AirBridgeTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content
    )
}
