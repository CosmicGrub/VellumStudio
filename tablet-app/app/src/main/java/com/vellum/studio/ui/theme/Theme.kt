package com.vellum.studio.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val VellumColorScheme = darkColorScheme(
    primary = InkAccentGold,
    onPrimary = InkOnGold,
    primaryContainer = InkGoldContainer,
    onPrimaryContainer = InkOnGoldContainer,
    secondary = InkAccentTeal,
    onSecondary = InkOnTeal,
    secondaryContainer = InkTealContainer,
    onSecondaryContainer = InkOnTealContainer,
    tertiary = InkRose,
    onTertiary = InkOnRose,
    tertiaryContainer = InkRoseContainer,
    onTertiaryContainer = InkOnRoseContainer,
    background = InkBackground,
    onBackground = InkOnBackground,
    surface = InkSurface,
    onSurface = InkOnBackground,
    surfaceVariant = InkSurfaceVariant,
    onSurfaceVariant = InkOnBackgroundDim,
    outline = InkOutline,
    outlineVariant = InkOutlineVariant,
    error = InkError,
    onError = InkOnError,
    errorContainer = InkErrorContainer,
    onErrorContainer = InkOnErrorContainer,
    inverseSurface = InkInverseSurface,
    inverseOnSurface = InkInverseOnSurface,
    inversePrimary = InkOnGoldContainer,
    scrim = InkScrim,
    surfaceTint = InkAccentGold,
)

/** Vellum Studio always runs a dark "atelier" theme — the canvas itself provides the light surface. */
@Composable
fun VellumStudioTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = VellumColorScheme,
        typography = VellumTypography,
        content = content,
    )
}
