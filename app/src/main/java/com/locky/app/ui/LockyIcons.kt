package com.locky.app.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Backspace
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Password
import androidx.compose.material.icons.outlined.Android
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * Icons used across the app, collected in one place so screens import from here
 * rather than from Compose Material directly.
 */
object LockyIcons {
    /** Fallback glyph for apps whose icon cannot be loaded. */
    val App: ImageVector = Icons.Outlined.Android

    val Backspace: ImageVector = Icons.Filled.Backspace
    val Fingerprint: ImageVector = Icons.Filled.Fingerprint

    /** Shown on the lock screen. */
    val Lock: ImageVector = Icons.Filled.Lock

    /** "End every grace period now" action. */
    val LockNow: ImageVector = Icons.Filled.Lock

    /** "Change PIN" action. */
    val ChangePin: ImageVector = Icons.Filled.Password
}
