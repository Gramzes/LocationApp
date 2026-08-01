package com.example.locationapp.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Палитра приложения.
val Brand = Color(0xFF4F6DF5)
val BrandDark = Color(0xFF3B54C7)
val BrandLight = Color(0xFFEAF0FF)
val Green = Color(0xFF22B573)
val Orange = Color(0xFFF2994A)
val Red = Color(0xFFE15241)
val Bg = Color(0xFFF4F6FB)
val Surface = Color(0xFFFFFFFF)
val TextPrimary = Color(0xFF1B2033)
val TextSecondary = Color(0xFF6B7280)
val TextMuted = Color(0xFFA0A6B4)

private val Colors = lightColorScheme(
    primary = Brand,
    onPrimary = Color.White,
    primaryContainer = BrandLight,
    onPrimaryContainer = BrandDark,
    secondary = Green,
    onSecondary = Color.White,
    background = Bg,
    onBackground = TextPrimary,
    surface = Surface,
    onSurface = TextPrimary,
    surfaceVariant = Surface,
    onSurfaceVariant = TextSecondary,
    error = Red
)

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Colors, content = content)
}
