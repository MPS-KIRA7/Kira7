package com.example.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.RoundedCornerShape

private val ReelStudioColorScheme = darkColorScheme(
    primary = AccentCyan,
    onPrimary = BgCanvas,
    primaryContainer = SurfaceElevated,
    onPrimaryContainer = TextPrimary,
    secondary = AccentIndigo,
    onSecondary = TextPrimary,
    secondaryContainer = SurfaceElevated,
    onSecondaryContainer = TextPrimary,
    tertiary = SuccessGreen,
    onTertiary = BgCanvas,
    background = BgCanvas,
    onBackground = TextPrimary,
    surface = SurfaceCard,
    onSurface = TextPrimary,
    surfaceVariant = SurfaceElevated,
    onSurfaceVariant = TextSecondary,
    outline = SurfaceBorder,
    error = ErrorRed,
    onError = TextPrimary
)

val ReelShapes = Shapes(
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp)
)

@Composable
fun ReelStudioTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = ReelStudioColorScheme,
        typography = Typography,
        shapes = ReelShapes,
        content = content
    )
}
