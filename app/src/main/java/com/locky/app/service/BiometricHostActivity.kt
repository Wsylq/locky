package com.locky.app.service

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.fragment.app.FragmentActivity
import com.locky.app.R
import com.locky.app.security.BiometricUnlock

/**
 * A transparent activity whose only job is to host [androidx.biometric.BiometricPrompt].
 *
 * The system prompt is a fragment, so it needs a `FragmentActivity` to attach to.
 * The lock overlay is drawn by the accessibility service and has no activity
 * behind it, so biometrics are requested by briefly bouncing here instead. The
 * activity has no UI of its own and finishes as soon as the prompt resolves.
 */
class BiometricHostActivity : FragmentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Nothing of this activity should ever be seen: the overlay stays on top
        // and the window is kept fully transparent.
        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE,
        )

        val packageName = intent.getStringExtra(EXTRA_PACKAGE_NAME)
        val appLabel = intent.getStringExtra(EXTRA_LABEL).orEmpty()

        if (packageName.isNullOrEmpty() || !BiometricUnlock.isAvailable(this)) {
            finish()
            return
        }

        BiometricUnlock.authenticate(
            activity = this,
            title = getString(R.string.pin_prompt_title),
            subtitle = getString(R.string.pin_prompt_subtitle, appLabel),
            onSuccess = {
                UnlockState.get(this).grant(packageName)
                AppWatcherService.onGateSatisfied(packageName)
                AppWatcherService.overlay()?.hide()
                AppWatcherService.releaseChallenge()
                finish()
            },
            onError = { message ->
                android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_SHORT)
                    .show()
                finish()
            },
        )
    }

    companion object {
        private const val EXTRA_PACKAGE_NAME = "com.locky.app.extra.BIO_PACKAGE_NAME"
        private const val EXTRA_LABEL = "com.locky.app.extra.BIO_LABEL"

        /**
         * Shows the system prompt, with the app's name in the subtitle.
         *
         * The label is passed separately because the caller has it already resolved
         * and cached: resolving it again from the package manager would put a binder
         * call on the path between the user tapping "biometrics" and the prompt
         * appearing, and would show a raw package name in the prompt.
         */
        fun launch(context: Context, packageName: String, appLabel: String) {
            val intent = Intent(context, BiometricHostActivity::class.java)
                .putExtra(EXTRA_PACKAGE_NAME, packageName)
                .putExtra(EXTRA_LABEL, appLabel)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { context.startActivity(intent) }
        }
    }
}
