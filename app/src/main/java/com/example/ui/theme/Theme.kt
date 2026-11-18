package com.example.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp

private val DarkColorScheme = darkColorScheme(
    primary = ElectricCyan,
    onPrimary = OnElectricCyan,
    primaryContainer = ElectricCyanContainer,
    onPrimaryContainer = CrispWhite,
    secondary = AmberSignal,
    onSecondary = OnAmberSignal,
    secondaryContainer = AmberSignalContainer,
    onSecondaryContainer = CrispWhite,
    tertiary = EmeraldPulse,
    onTertiary = OnElectricCyan,
    tertiaryContainer = EmeraldPulseContainer,
    onTertiaryContainer = CrispWhite,
    background = SlateNavyDark,
    onBackground = CrispWhite,
    surface = SlateSurfaceDark,
    onSurface = CrispWhite,
    surfaceVariant = SlateSurfaceElevated,
    onSurfaceVariant = MutedSlateText,
    outline = SlateOutlineDark
)

private val LightColorScheme = lightColorScheme(
    primary = DeepCyanLight,
    onPrimary = SlateSurfaceLight,
    primaryContainer = SlateSurfaceVariantLight,
    onPrimaryContainer = InkDarkText,
    secondary = DeepAmberLight,
    onSecondary = SlateSurfaceLight,
    secondaryContainer = SlateSurfaceVariantLight,
    onSecondaryContainer = InkDarkText,
    tertiary = DeepEmeraldLight,
    onTertiary = SlateSurfaceLight,
    tertiaryContainer = SlateSurfaceVariantLight,
    onTertiaryContainer = InkDarkText,
    background = SlateBackgroundLight,
    onBackground = InkDarkText,
    surface = SlateSurfaceLight,
    onSurface = InkDarkText,
    surfaceVariant = SlateSurfaceVariantLight,
    onSurfaceVariant = InkMutedText,
    outline = SlateOutlineLight
)

val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(18.dp),
    extraLarge = RoundedCornerShape(24.dp)
)

@Composable
fun MyApplicationTheme(
    darkTheme: Boolean = true,
    content: @Composable () -> Unit,
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        shapes = AppShapes,
        content = content
    )
}
