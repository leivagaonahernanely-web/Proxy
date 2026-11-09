package com.northproxy.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val Bg = Color(0xFF05060A)
val Surface1 = Color(0xFF0E1119)
val Surface2 = Color(0xFF151A26)
val NorthRed = Color(0xFFFF2233)
val NorthRedDark = Color(0xFF8E0F19)
val TextMain = Color(0xFFF2F4F8)
val TextDim = Color(0xFF8A93A6)
val Online = Color(0xFF2BE07A)

private val Scheme = darkColorScheme(
    primary = NorthRed,
    onPrimary = Color.White,
    background = Bg,
    onBackground = TextMain,
    surface = Surface1,
    onSurface = TextMain,
    surfaceVariant = Surface2,
    onSurfaceVariant = TextDim
)

@Composable
fun NorthTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Scheme, content = content)
}
