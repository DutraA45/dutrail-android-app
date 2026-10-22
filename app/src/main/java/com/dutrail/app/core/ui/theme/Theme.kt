package com.dutrail.app.core.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val TrailOrange = Color(0xFFE4572E)
private val TrailOrangeLight = Color(0xFFFFB59D)
private val Forest = Color(0xFF2E6B4F)
private val ForestLight = Color(0xFF9BD3B5)

private val LightColors = lightColorScheme(
    primary = TrailOrange,
    onPrimary = Color.White,
    secondary = Forest,
    onSecondary = Color.White,
)

private val DarkColors = darkColorScheme(
    primary = TrailOrangeLight,
    onPrimary = Color(0xFF5C1900),
    secondary = ForestLight,
    onSecondary = Color(0xFF003824),
)

@Composable
fun DutrailTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = Typography(),
        content = content,
    )
}
