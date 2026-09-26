package com.locky.app.service

import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.fragment.app.FragmentActivity
import com.locky.app.R
import com.locky.app.security.AttemptLimiter
import com.locky.app.security.BiometricUnlock
import com.locky.app.ui.LockScreenRoute
import com.locky.app.ui.theme.LockyLockTheme

/**
 * The PIN gate shown in front of a protected app.
 *
 * It is a separate, non-exported activity on its own task affinity so that it can
 * cover the locked app without joining that app's task. On success the target
 * package is granted a grace period in [UnlockState] and this screen closes,
 * revealing the app underneath.
 *
 * It extends [FragmentActivity] so the platform [BiometricPrompt] can attach for
 * the biometric alternative to the PIN.
 */
class LockScreenActivity : FragmentActivity() {

    private val attemptLimiter = AttemptLimiter()

    /** Empty until onCreate reads the intent; the lock screen is meaningless without it. */
    private var targetPackage: String = ""
    private var targetLabel: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // A secure window keeps the PIN and the app's contents out of screenshots
        // and the recents thumbnail.
        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE,
        )

        val pkg = intent.getStringExtra(EXTRA_PACKAGE_NAME)
        if (pkg.isNullOrEmpty()) {
            finish()
            return
        }
        targetPackage = pkg
        targetLabel = intent.getStringExtra(EXTRA_LABEL)
            ?: getString(R.string.pin_prompt_subtitle_generic)

        setContent {
            LockyLockTheme {
                LockScreenRoute(
                    appLabel = targetLabel,
                    attemptLimiter = attemptLimiter,
                    onUseBiometrics = {
                        // Only offer biometrics when the device actually has a
                        // secure lock screen configured.
                        if (BiometricUnlock.isAvailable(this)) {
                            BiometricUnlock.authenticate(
                                activity = this,
                                title = getString(R.string.pin_prompt_title),
                                subtitle = getString(R.string.pin_prompt_subtitle, targetLabel),
                                onSuccess = { onUnlocked(targetPackage) },
                                onError = ::showMessage,
                            )
                        } else {
                            showMessage(getString(R.string.biometrics_unavailable))
                        }
                    },
                    onUnlocked = ::onUnlocked,
                )
            }
        }
    }

    private fun onUnlocked(packageName: String) {
        if (packageName.isEmpty()) return
        UnlockState.get(this).grant(packageName)
        AppWatcherService.releaseChallenge()
        finish()
    }

    private fun showMessage(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    override fun onDestroy() {
        // If the screen is torn down without a successful unlock, make sure the
        // watcher is able to challenge again.
        if (isFinishing) AppWatcherService.releaseChallenge()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_PACKAGE_NAME = "com.locky.app.extra.PACKAGE_NAME"
        const val EXTRA_LABEL = "com.locky.app.extra.LABEL"
    }
}
