package com.locky.app.ui

import android.content.Context
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Backspace
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Password
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.outlined.Android
import androidx.compose.ui.graphics.vector.ImageVector
import com.locky.app.security.BiometricHardware

/**
 * Icons used across the app, collected in one place so screens import from here
 * rather than from Compose Material directly.
 */
object LockyIcons {
    /** Fallback glyph for apps whose icon cannot be loaded. */
    val App: ImageVector = Icons.Outlined.Android

    val Backspace: ImageVector = Icons.Filled.Backspace
    val Face: ImageVector = Icons.Filled.Face
    val Fingerprint: ImageVector = Icons.Filled.Fingerprint

    /** Shown on the lock screen. */
    val Lock: ImageVector = Icons.Filled.Lock

    /** "End every grace period now" action. */
    val LockNow: ImageVector = Icons.Filled.Lock

    /** "Change PIN" action. */
    val ChangePin: ImageVector = Icons.Filled.Password

    /** Settings. */
    val Settings: ImageVector = Icons.Filled.Tune

    /**
     * The glyph for "unlock with biometrics", matched to the hardware present.
     *
     * A hardcoded fingerprint is a small lie on a phone with a face sensor and no
     * fingerprint reader: it promises a gesture the user cannot perform. Android
     * offers no way to ask which modality is actually enrolled, so the hardware is
     * used instead, and only as a tiebreak — a phone with both keeps the
     * fingerprint, which is the one people reach for first.
     */
    fun unlockIcon(context: Context): ImageVector {
        val face = BiometricHardware.hasFace(context)
        val fingerprint = BiometricHardware.hasFingerprint(context)
        return when {
            face && !fingerprint -> Face
            fingerprint -> Fingerprint
            face -> Face
            else -> Fingerprint
        }
    }
}
