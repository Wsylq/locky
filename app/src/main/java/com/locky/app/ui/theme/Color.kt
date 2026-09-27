package com.locky.app.ui.theme

import androidx.compose.ui.graphics.Color

// Light scheme
val Blue40 = Color(0xFF2563EB)
val Blue80 = Color(0xFF93B4FF)
val Slate10 = Color(0xFF0B1020)
val Slate20 = Color(0xFF141A2E)
val Slate90 = Color(0xFFE4E7F0)
val Slate95 = Color(0xFFF3F5FA)
val Slate30 = Color(0xFF2A3350)
val Slate50 = Color(0xFF6B7488)
val SlateLightSurface = Color(0xFFFFFFFF)

/*
 * Dark scheme.
 *
 * Deliberately not neutral grey. Every step carries the same cool blue cast, so
 * the lock screen reads as one deliberate surface rather than a dark mode that
 * happens to be switched on. The background is nearly black and the elevation
 * steps are wide apart, which is what gives the keypad and the lock badge
 * somewhere to sit instead of floating on flat black.
 */
val DarkBackground = Color(0xFF06080F)
val DarkSurface = Color(0xFF0B1020)
val DarkSurfaceVariant = Color(0xFF151B2E)

/** Text on those surfaces. Not pure white, which glares against a near-black. */
val DarkOnSurface = Color(0xFFEAEDF7)
val DarkOnSurfaceVariant = Color(0xFF96A0BC)

/**
 * The single accent.
 *
 * A desaturated periwinkle rather than a saturated blue: it has to stay legible
 * as small text on near-black and as a large filled button without either
 * glowing or looking like a link.
 */
val AccentBlue = Color(0xFF7AA2FF)

val ErrorRed = Color(0xFFFF6B81)

/**
 * Translucent layers, for the parts of the lock screen that should feel like
 * glass sitting above the background rather than panels painted on it.
 *
 * Defined as white with an alpha rather than as tinted greys so they pick up
 * whatever is behind them, which is what keeps the overlay from looking flat
 * when it lands on a photo or a bright app.
 */
val GlassFill = Color(0x14FFFFFF)
val GlassOutline = Color(0x1FFFFFFF)
val AccentGlassFill = Color(0x1F7AA2FF)
val AccentOutline = Color(0x597AA2FF)

/** The wash behind the lock badge, fading to nothing. */
val AccentGlow = Color(0x2E7AA2FF)
