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
import com.locky.app.ui.LockGate
import com.locky.app.ui.theme.LockyLockTheme

/**
 * Fallback lock screen, used only when the "display over other apps" permission
 * has been denied.
 *
 * The normal path is [LockOverlayController], which draws the same [LockGate]
 * composable directly over the foreground app with no activity involved. This
 * exists so that denying the overlay permission degrades to a slightly slower
 * lock rather than to no lock at all.
 */
class LockScreenActivity : FragmentActivity() {

    // Shared process-wide, not per-activity: this activity is created and
    // destroyed on every launch, so a fresh limiter here would reset the lock-out
    // counter each time and make it meaningless.
    private val attemptLimiter = AttemptLimiter.shared()

    private var targetPackage: String = ""
    private var targetLabel: String = ""

    /** Guards against releasing the watcher challenge after a successful unlock. */
    private var didUnlock = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Keeps the PIN and the app's contents out of screenshots and the recents
        // thumbnail.
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
                LockGate(
                    packageName = targetPackage,
                    appLabel = targetLabel,
                    attemptLimiter = attemptLimiter,
                    onUnlocked = ::onUnlocked,
                    onUseBiometrics = ::promptForBiometrics,
                )
            }
        }
    }

    private fun onUnlocked(packageName: String) {
        if (packageName.isEmpty()) return
        didUnlock = true
        UnlockState.get(this).grant(packageName)
        AppWatcherService.releaseChallenge()
        finish()
    }

    override fun onDestroy() {
        // If the user dismissed this screen without unlocking, the watcher's
        // challenge has to be released or it will refuse to gate anything for the
        // rest of the process's life.
        if (!didUnlock) AppWatcherService.releaseChallenge()
        AppWatcherService.endBiometric()
        super.onDestroy()
    }

    private fun promptForBiometrics() {
        if (!BiometricUnlock.isAvailable(this)) {
            showMessage(getString(R.string.biometrics_unavailable))
            return
        }
        // Same reason as the overlay path: the system prompt is its own foreground
        // window, and the watcher must not read that as the user leaving the app
        // they are being let into.
        AppWatcherService.beginBiometric()
        BiometricUnlock.authenticate(
            activity = this,
            title = getString(R.string.pin_prompt_title),
            subtitle = getString(R.string.pin_prompt_subtitle, targetLabel),
            onSuccess = { onUnlocked(targetPackage) },
            onError = ::showMessage,
        )
    }

    private fun showMessage(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    companion object {
        const val EXTRA_PACKAGE_NAME = "com.locky.app.extra.PACKAGE_NAME"
        const val EXTRA_LABEL = "com.locky.app.extra.LABEL"
    }
}
