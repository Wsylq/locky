package com.locky.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.locky.app.LockyApp
import com.locky.app.R
import com.locky.app.security.AttemptLimiter
import com.locky.app.service.LockScreenActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Longest PIN the app accepts. */
const val MAX_PIN_LENGTH = 8

/** Shortest PIN the app accepts. */
const val MIN_PIN_LENGTH = 4

/**
 * The lock screen's stateful route.
 *
 * Verification lives here rather than in the activity so the PIN field, the error
 * message and the busy state stay consistent: a wrong PIN always clears the
 * field, and a correct one always hands control back to the host.
 */
@Composable
fun LockScreenRoute(
    appLabel: String,
    attemptLimiter: AttemptLimiter,
    onUseBiometrics: () -> Unit,
    onUnlocked: (String) -> Unit,
) {
    val context = LocalContext.current
    val activity = context as? LockScreenActivity
    val scope = rememberCoroutineScope()

    var pin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var isBusy by remember { mutableStateOf(false) }

    // The activity is only absent in previews and tests; there is nothing to
    // unlock in that case, so render the keypad inert rather than crashing.
    val targetPackage = remember(activity) {
        activity?.intent?.getStringExtra(LockScreenActivity.EXTRA_PACKAGE_NAME)
    }

    fun submit() {
        val host = activity ?: return
        if (isBusy) return

        if (attemptLimiter.isLockedOut) {
            error = context.getString(
                R.string.pin_locked_out,
                formatCountdown(attemptLimiter.lockoutRemainingMillis),
            )
            return
        }

        isBusy = true
        scope.launch {
            // PBKDF2 at 120k iterations is intentionally slow, so it must not run
            // on the main thread or the keypad drops frames while it works.
            val correct = withContext(Dispatchers.Default) {
                LockyApp.from(host).pinManager.verify(pin)
            }
            isBusy = false

            if (correct) {
                attemptLimiter.onSuccess()
                onUnlocked(targetPackage.orEmpty())
            } else {
                attemptLimiter.onFailure()
                pin = ""
                error = context.getString(R.string.pin_wrong)
            }
        }
    }

    // Auto-submit once the PIN reaches its natural length, which is what people
    // expect from a four-to-eight digit code.
    LaunchedEffect(pin) {
        if (pin.length >= MIN_PIN_LENGTH && !isBusy) {
            delay(AUTO_SUBMIT_DELAY_MILLIS)
            if (pin.length >= MIN_PIN_LENGTH) submit()
        }
    }

    // Tick down a visible cool-off so the user can see it expiring.
    if (attemptLimiter.isLockedOut) {
        LaunchedEffect(attemptLimiter.lockoutRemainingMillis) {
            while (attemptLimiter.isLockedOut) {
                delay(1_000)
            }
            error = null
        }
    }

    PinEntryScreen(
        appLabel = appLabel,
        pin = pin,
        errorMessage = error,
        isBusy = isBusy,
        remainingAttempts = attemptLimiter.remainingAttempts,
        isLockedOut = attemptLimiter.isLockedOut,
        lockoutMillis = attemptLimiter.lockoutRemainingMillis,
        onDigit = { digit ->
            if (!isBusy && pin.length < MAX_PIN_LENGTH) {
                error = null
                pin += digit
            }
        },
        onBackspace = {
            if (!isBusy && pin.isNotEmpty()) {
                error = null
                pin = pin.dropLast(1)
            }
        },
        onSubmit = ::submit,
        onUseBiometrics = onUseBiometrics,
    )
}

/** Stateless lock screen, previewable on its own. */
@Composable
fun PinEntryScreen(
    appLabel: String,
    pin: String,
    errorMessage: String?,
    isBusy: Boolean,
    remainingAttempts: Int,
    isLockedOut: Boolean,
    lockoutMillis: Long,
    onDigit: (Char) -> Unit,
    onBackspace: () -> Unit,
    onSubmit: () -> Unit,
    onUseBiometrics: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 28.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = LockyIcons.Lock,
            contentDescription = null,
            modifier = Modifier.size(40.dp),
            tint = MaterialTheme.colorScheme.primary,
        )

        Spacer(Modifier.height(16.dp))

        Text(
            text = stringResource(R.string.pin_prompt_title),
            style = MaterialTheme.typography.headlineSmall,
        )

        Spacer(Modifier.height(4.dp))

        Text(
            text = appLabel,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )

        Spacer(Modifier.height(28.dp))

        PinDots(length = pin.length, maxLength = MAX_PIN_LENGTH)

        Spacer(Modifier.height(12.dp))

        // Reserve the row so the keypad does not jump when an error appears.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(40.dp),
            contentAlignment = Alignment.Center,
        ) {
            when {
                isBusy -> CircularProgressIndicator(modifier = Modifier.size(20.dp))

                errorMessage != null -> Text(
                    text = errorMessage,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center,
                )

                else -> Text(
                    text = remainingAttempts.toString(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Spacer(Modifier.height(16.dp))

        PinKeypad(
            onDigit = onDigit,
            onBackspace = onBackspace,
            modifier = Modifier.fillMaxWidth(0.9f),
        )

        Spacer(Modifier.height(12.dp))

        TextButton(
            onClick = onUseBiometrics,
            enabled = !isBusy && !isLockedOut,
        ) {
            Icon(
                imageVector = LockyIcons.Fingerprint,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.size(8.dp))
            Text(stringResource(R.string.action_use_biometrics))
        }
    }
}

private const val AUTO_SUBMIT_DELAY_MILLIS = 120L

private fun formatCountdown(millis: Long): String {
    val totalSeconds = (millis / 1000).coerceAtLeast(1)
    return "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
}
