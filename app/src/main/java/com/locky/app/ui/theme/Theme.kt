package com.locky.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val LightColors = lightColorScheme(
    primary = Blue40,
    onPrimary = SlateLightSurface,
    primaryContainer = Slate95,
    onPrimaryContainer = Slate30,
    secondary = Slate50,
    background = SlateLightSurface,
    onBackground = Slate20,
    surface = SlateLightSurface,
    onSurface = Slate20,
    surfaceVariant = Slate95,
    onSurfaceVariant = Slate50,
    error = ErrorRed,
)

private val LockColors = darkColorScheme(
    primary = AccentBlue,
    onPrimary = DarkBackground,
    primaryContainer = DarkSurfaceVariant,
    onPrimaryContainer = DarkOnSurface,
    background = DarkBackground,
    onBackground = DarkOnSurface,
    surface = DarkSurface,
    onSurface = DarkOnSurface,
    surfaceVariant = DarkSurfaceVariant,
    onSurfaceVariant = DarkOnSurfaceVariant,
    error = ErrorRed,
)

/** Theme for the main app UI. */
@Composable
fun LockyTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) LockColors else LightColors,
        typography = LockyTypography,
        content = content,
    )
}

/**
 * Theme for the lock screen.
 *
 * Always dark, on purpose: it is an overlay in front of whatever the user was
 * trying to open, and it should read the same no matter what is behind it.
 */
@Composable
fun LockyLockTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = LockColors,
        typography = LockyTypography,
        content = content,
    )
}
