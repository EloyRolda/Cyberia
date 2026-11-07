package app.cyberia.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Colores del ícono: pelo castaño, sombra y brillo.
private val Brown = Color(0xFF643829)
private val BrownDark = Color(0xFF3B1A11)
private val Ink = Color(0xFF24100A)
private val Highlight = Color(0xFFBF8F7A)
private val Cream = Color(0xFFF7F2EF)
private val Blush = Color(0xFFF1E4DE)

private val LightColors = lightColorScheme(
    primary = Brown,
    onPrimary = Color.White,
    primaryContainer = Blush,
    onPrimaryContainer = BrownDark,
    secondary = Highlight,
    onSecondary = Ink,
    secondaryContainer = Blush,
    onSecondaryContainer = BrownDark,
    background = Cream,
    onBackground = Ink,
    surface = Cream,
    onSurface = Ink,
    surfaceVariant = Blush,
    onSurfaceVariant = Color(0xFF7A6158),
    surfaceContainer = Color(0xFFF2E9E4),
    surfaceContainerHigh = Color(0xFFEDE1DB),
    outline = Color(0xFFD6C3BA),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFD9A58E),
    onPrimary = BrownDark,
    primaryContainer = Color(0xFF4A2A1E),
    onPrimaryContainer = Blush,
    secondary = Highlight,
    onSecondary = Ink,
    secondaryContainer = Color(0xFF3B2117),
    onSecondaryContainer = Blush,
    background = Color(0xFF1A0E0A),
    onBackground = Color(0xFFF5E9E3),
    surface = Color(0xFF1A0E0A),
    onSurface = Color(0xFFF5E9E3),
    surfaceVariant = Color(0xFF3B2117),
    onSurfaceVariant = Color(0xFFC0A69B),
    surfaceContainer = Color(0xFF26150F),
    surfaceContainerHigh = Color(0xFF301B13),
    outline = Color(0xFF5A3A2C),
)

@Composable
fun CyberiaTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        content = content,
    )
}
