package fr.opta.testmobile.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = Color(0xFF0B6E4F),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD8F3E8),
    onPrimaryContainer = Color(0xFF002117),
    secondary = Color(0xFF405F58),
    background = Color(0xFFF4F6F8),
    surface = Color.White,
    surfaceVariant = Color(0xFFE7ECEA),
    error = Color(0xFFBA1A1A)
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF73D8B5),
    onPrimary = Color(0xFF003828),
    primaryContainer = Color(0xFF00513B),
    onPrimaryContainer = Color(0xFF8EF5D0),
    secondary = Color(0xFFA7CEC2),
    background = Color(0xFF101418),
    surface = Color(0xFF171C20),
    surfaceVariant = Color(0xFF25302D),
    error = Color(0xFFFFB4AB)
)

@Composable
fun OptaTestMobileTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = AppTypography,
        content = content
    )
}
