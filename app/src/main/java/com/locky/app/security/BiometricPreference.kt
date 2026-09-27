package com.locky.app.security

import android.content.Context
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * What the device can actually authenticate with.
 *
 * Read from the platform rather than guessed, because the answer decides what the
 * lock screen can offer and "nothing happened" is otherwise indistinguishable
 * from "this phone cannot do it".
 */
data class BiometricCapabilities(
    /** A Class 3 (strong) biometric is enrolled: a good fingerprint, or a face at Class 3. */
    val strongBiometrics: Boolean,
    /** A Class 2 (weak) biometric is enrolled. Commonly a face on most phones. */
    val weakBiometrics: Boolean,
    /** The device has a PIN, pattern or password. */
    val deviceCredential: Boolean,
) {
    /**
     * True when accepting a face would mean accepting a weak biometric.
     *
     * Most phones classify face unlock as Class 2, which Android will not hand to
     * a `BIOMETRIC_STRONG` request at all. So on a typical handset "add face
     * unlock" means "relax the requirement", and that is a security decision the
     * user has to make rather than one to make silently on their behalf.
     */
    val faceNeedsWeakerBiometrics: Boolean
        get() = weakBiometrics && !strongBiometrics
}

/**
 * Whether Locky is allowed to accept a weak biometric.
 *
 * Off by default, and the default is the point. Class 2 biometrics are documented
 * by the platform as being spoofable — a photograph may be enough for some
 * sensors — and an app lock that silently accepted one would be weaker than the
 * phone's own lock screen, which is the opposite of what a lock is for.
 *
 * Stored as a preference rather than compiled in so the choice is visible and
 * reversible, and so the setting can name what it actually does.
 */
object BiometricPreference {

    private const val PREFS_NAME = "locky_security"
    private const val KEY_ALLOW_WEAK = "allow_weak_biometrics"

    /**
     * A reactive copy of the stored value.
     *
     * SharedPreferences is not observable, and that matters more here than it looks:
     * the lock gate is built once and then shown and hidden, so a composable that
     * remembers "can this phone do biometrics" keeps its answer from the first time
     * the lock ever appeared. Flipping this setting would then appear to do nothing
     * until the process restarted. Mirroring the value gives already-composed UI
     * something to observe.
     *
     * Equal values do not re-emit, so writing the same value on every read is free.
     */
    private val _allowsWeak = MutableStateFlow(false)
    val allowsWeakFlow: StateFlow<Boolean> = _allowsWeak.asStateFlow()

    fun allowsWeakBiometrics(context: Context): Boolean {
        val stored = prefs(context).getBoolean(KEY_ALLOW_WEAK, false)
        _allowsWeak.value = stored
        return stored
    }

    fun setAllowsWeakBiometrics(context: Context, allowed: Boolean) {
        prefs(context).edit().putBoolean(KEY_ALLOW_WEAK, allowed).apply()
        _allowsWeak.value = allowed
    }

    /**
     * The authenticators to request, given the user's choice.
     *
     * Device credentials are always allowed alongside, so the prompt can always
     * fall back to the phone's own PIN and unlocking is never weaker than that.
     */
    fun authenticatorsFor(context: Context): Int =
        if (allowsWeakBiometrics(context)) {
            BIOMETRIC_WEAK or DEVICE_CREDENTIAL
        } else {
            BIOMETRIC_STRONG or DEVICE_CREDENTIAL
        }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}

/**
 * Which biometric hardware the device physically has.
 *
 * Separate from [BiometricCapabilities] on purpose: that says what is *enrolled*,
 * this says what the hardware *is*. A phone with a face sensor and nothing set up
 * on it reports neither, and the difference decides which glyph to draw and which
 * sentence to show.
 *
 * The feature names are checked as strings. Android documents them but only
 * exposes some of them as constants from API 29, and this app supports 26, so
 * referencing the constants would mean either an annotation or a version guard
 * around something that is a plain string either way.
 */
object BiometricHardware {

    private const val FEATURE_FACE = "android.hardware.biometrics.face"
    private const val FEATURE_FINGERPRINT = "android.hardware.fingerprint"

    fun hasFace(context: Context): Boolean = hasFeature(context, FEATURE_FACE)

    fun hasFingerprint(context: Context): Boolean = hasFeature(context, FEATURE_FINGERPRINT)

    private fun hasFeature(context: Context, feature: String): Boolean =
        runCatching { context.packageManager.hasSystemFeature(feature) }.getOrDefault(false)
}
