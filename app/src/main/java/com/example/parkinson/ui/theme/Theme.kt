package com.example.parkinson.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection

private val MedicalLightColorScheme = lightColorScheme(
    primary = MedicalPrimary,
    onPrimary = MedicalOnPrimary,
    primaryContainer = MedicalPrimaryContainer,
    onPrimaryContainer = MedicalOnPrimaryContainer,
    secondary = MedicalSecondary,
    onSecondary = MedicalOnSecondary,
    secondaryContainer = MedicalSecondaryContainer,
    onSecondaryContainer = MedicalOnSecondaryContainer,
    background = MedicalBackground,
    onBackground = MedicalOnBackground,
    surface = MedicalSurface,
    onSurface = MedicalOnSurface,
    surfaceVariant = MedicalSurfaceVariant,
    onSurfaceVariant = MedicalOnSurfaceVariant,
    outline = MedicalOutline,
    error = MedicalError,
    onError = MedicalOnError,
    errorContainer = MedicalErrorContainer
)

private val MedicalDarkColorScheme = darkColorScheme(
    primary = MedicalPrimary,
    onPrimary = MedicalOnPrimary,
    primaryContainer = MedicalPrimaryContainer,
    onPrimaryContainer = MedicalOnPrimaryContainer,
    secondary = MedicalSecondary,
    onSecondary = MedicalOnSecondary,
    secondaryContainer = MedicalSecondaryContainer,
    onSecondaryContainer = MedicalOnSecondaryContainer,
    background = MedicalBackground,
    onBackground = MedicalOnBackground,
    surface = MedicalSurface,
    onSurface = MedicalOnSurface,
    surfaceVariant = MedicalSurfaceVariant,
    onSurfaceVariant = MedicalOnSurfaceVariant,
    outline = MedicalOutline,
    error = MedicalError,
    onError = MedicalOnError,
    errorContainer = MedicalErrorContainer
)

@Composable
fun ParkinsonTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) MedicalDarkColorScheme else MedicalLightColorScheme

    CompositionLocalProvider(
        LocalLayoutDirection provides LayoutDirection.Rtl
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = Typography,
            shapes = Shapes,
            content = content
        )
    }
}
