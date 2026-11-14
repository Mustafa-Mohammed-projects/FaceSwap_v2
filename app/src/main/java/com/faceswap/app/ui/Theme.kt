package com.faceswap.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Scheme = darkColorScheme(
    primary = Color(0xFFB388FF),
    onPrimary = Color(0xFF1B0B3A),
    primaryContainer = Color(0xFF33265C),
    onPrimaryContainer = Color(0xFFE9DDFF),
    secondary = Color(0xFFFF7EB6),
    tertiary = Color(0xFF5EEAD4),
    background = Color(0xFF0D0E14),
    onBackground = Color(0xFFE8E9F3),
    surface = Color(0xFF161823),
    onSurface = Color(0xFFE8E9F3),
    surfaceVariant = Color(0xFF20233A),
    onSurfaceVariant = Color(0xFF9DA3BD),
    outline = Color(0xFF2D3150),
    error = Color(0xFFFF6B7A),
)

@Composable
fun FaceSwapTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Scheme, typography = Typography(), content = content)
}
