package com.dsh.overlaytranslator.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightScheme = lightColorScheme(
    primary = Color(0xFF3D5AFE),
    onPrimary = Color.White,
    secondary = Color(0xFF5C6BC0),
    onSecondary = Color.White,
    background = Color(0xFFF7F8FC),
    surface = Color.White,
    surfaceVariant = Color(0xFFE8EAF6),
    error = Color(0xFFD32F2F),
)

private val DarkScheme = darkColorScheme(
    primary = Color(0xFF7C9CFF),
    onPrimary = Color(0xFF0B1020),
    secondary = Color(0xFF9FA8DA),
    onSecondary = Color(0xFF0B1020),
    background = Color(0xFF10131A),
    surface = Color(0xFF181B22),
    surfaceVariant = Color(0xFF232733),
    error = Color(0xFFFF6B6B),
)

@Composable
fun OverlayTranslatorTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkScheme else LightScheme,
        content = content,
    )
}
