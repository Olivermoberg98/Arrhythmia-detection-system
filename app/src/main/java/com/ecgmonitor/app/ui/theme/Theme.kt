package com.ecgmonitor.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColorScheme = darkColorScheme(
    primary = MedicalGreen,
    secondary = MedicalCyan,
    tertiary = MedicalOrange,
    background = MedicalDarkBackground,
    surface = MedicalCardBackground,
    error = MedicalRed,
    onPrimary = Color.Black,
    onSecondary = Color.Black,
    onTertiary = Color.Black,
    onBackground = MedicalTextPrimary,
    onSurface = MedicalTextPrimary,
    onError = Color.White
)

@Composable
fun EcgMonitorTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = DarkColorScheme,
        typography = Typography,
        content = content
    )
}
