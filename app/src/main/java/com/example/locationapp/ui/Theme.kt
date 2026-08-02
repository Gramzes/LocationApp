package com.example.locationapp.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Тёмная палитра с лаймовым акцентом.
val Brand = Color(0xFFC9F24D)        // лаймовый акцент
val BrandDark = Color(0xFF2A2A30)    // тёмная второстепенная кнопка/иконка
val BrandLight = Color(0xFF26262B)   // приподнятый чип/плитка
val OnAccent = Color(0xFF15170D)     // тёмный текст на лайме
val Green = Color(0xFFB7F03A)        // «верно» / успех
val Orange = Color(0xFFF2A24A)
val Red = Color(0xFFFF5A5F)
val Bg = Color(0xFF0F0F11)           // фон
val Surface = Color(0xFF1B1B1E)      // карточка
val TextPrimary = Color(0xFFF5F5F7)
val TextSecondary = Color(0xFF9A9AA2)
val TextMuted = Color(0xFF6E6E76)

private val Colors = darkColorScheme(
    primary = Brand,
    onPrimary = OnAccent,
    primaryContainer = BrandLight,
    onPrimaryContainer = TextPrimary,
    secondary = Brand,
    onSecondary = OnAccent,
    background = Bg,
    onBackground = TextPrimary,
    surface = Surface,
    onSurface = TextPrimary,
    surfaceVariant = BrandLight,
    onSurfaceVariant = TextSecondary,
    error = Red,
    onError = Color.White,
    outline = Color(0xFF3A3A42)
)

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Colors, content = content)
}
