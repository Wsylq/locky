package com.locky.app.security

import android.content.Context
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.activity.ComponentActivity
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.locky.app.R
import java.util.concurrent.Executor

/**
 * Device-credential / biometric unlock, used as an alternative to the PIN.
 *
 * The prompt deliberately requires device credentials rather than a bare
 * biometric, so unlocking never becomes weaker than the phone's own lock screen.
 */
object BiometricUnlock {

    /**
     * True when the device has a secure lock screen that can back an unlock.
     */
    fun isAvailable(context: Context): Boolean {
        val manager = BiometricManager.from(context)
        return manager.canAuthenticate(AUTHENTICATORS) == BiometricManager.BIOMETRIC_SUCCESS
    }

    /**
     * Shows the system unlock prompt.
     *
     * [activity] must be a [FragmentActivity]: the platform prompt is itself a
     * fragment, so Locky's activities extend it rather than [ComponentActivity].
     * [ComponentActivity] is a supertype of [FragmentActivity], so `setContent`
     * and Compose still work normally.
     */
    fun authenticate(
        activity: FragmentActivity,
        title: String,
        subtitle: String,
        onSuccess: () -> Unit,
        onError: (String) -> Unit,
    ) {
        val executor: Executor = ContextCompat.getMainExecutor(activity)
        val prompt = BiometricPrompt(
            activity,
            executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    onSuccess()
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    // The user cancelling is normal, not a failure worth shouting about.
                    if (errorCode != BiometricPrompt.ERROR_USER_CANCELED &&
                        errorCode != BiometricPrompt.ERROR_NEGATIVE_BUTTON
                    ) {
                        onError(errString.toString())
                    }
                }
            },
        )

        // The prompt is now raised without the user asking for it, so it has to
        // survive being called a moment too early: BiometricPrompt throws if the
        // host activity has not resumed yet or has already saved its state. A
        // failure here must not take the lock screen down with it, so it is
        // reported and the PIN keypad stays available.
        runCatching {
            prompt.authenticate(
                BiometricPrompt.PromptInfo.Builder()
                    .setTitle(title)
                    .setSubtitle(subtitle)
                    .setAllowedAuthenticators(AUTHENTICATORS)
                    .build(),
            )
        }.onFailure {
            onError(activity.getString(R.string.biometrics_prompt_failed))
        }
    }

    private const val AUTHENTICATORS =
        BiometricManager.Authenticators.BIOMETRIC_STRONG or
            BiometricManager.Authenticators.DEVICE_CREDENTIAL
}
