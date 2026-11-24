package io.github.juanfunkin.wiiudownloader.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Wii U cyan, kept fixed (no dynamic color) so the app keeps its own identity.
private val LightColors = lightColorScheme(
    primary = Color(0xFF0096D6),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD0ECFA),
    onPrimaryContainer = Color(0xFF00344D),
    secondary = Color(0xFF4F6372),
    secondaryContainer = Color(0xFFD2E4F2),
    background = Color(0xFFFBFCFE),
    surface = Color(0xFFFBFCFE),
    surfaceVariant = Color(0xFFDDE3EA),
    error = Color(0xFFBA1A1A),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF5CC8FF),
    onPrimary = Color(0xFF00344D),
    primaryContainer = Color(0xFF004B6E),
    onPrimaryContainer = Color(0xFFD0ECFA),
    secondary = Color(0xFFB7C9D8),
    secondaryContainer = Color(0xFF384A58),
    background = Color(0xFF101418),
    surface = Color(0xFF101418),
    surfaceVariant = Color(0xFF41484F),
    error = Color(0xFFFFB4AB),
)

@Composable
fun WiiUTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        content = content,
    )
}
