package com.tenniscount.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.sp

private val TennisBall = Color(0xFFD7FF3E)

private val DarkColors = darkColorScheme(
    primary = TennisBall,
    onPrimary = Color.Black,
    secondary = Color(0xFF9CCC65),
    background = Color.Black,
    surface = Color(0xFF121212),
    onBackground = Color.White,
    onSurface = Color.White,
    surfaceVariant = Color(0xFF1E1E1E),
    onSurfaceVariant = Color(0xFFCCCCCC),
    error = Color(0xFFFF5252),
)

private val AppTypography = Typography(
    headlineLarge = Typography().headlineLarge.copy(fontSize = 34.sp),
    headlineSmall = Typography().headlineSmall.copy(fontSize = 26.sp),
    bodyLarge = Typography().bodyLarge.copy(fontSize = 20.sp),
    bodyMedium = Typography().bodyMedium.copy(fontSize = 18.sp),
)

@Composable
fun TennisCountTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColors,
        typography = AppTypography,
        content = content,
    )
}
