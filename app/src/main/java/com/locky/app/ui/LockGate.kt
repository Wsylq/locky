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
import androidx.compose.runtime.mutableIntStateOf
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Longest PIN the app accepts. */
const val MAX_PIN_LENGTH = 8

/** Shortest PIN the app accepts. */
const val MIN_PIN_LENGTH = 4

private const val AUTO_SUBMIT_DELAY_MILLIS = 120L

/**
 * The PIN gate, with all of its own state.
 *
 * Shared by the overlay and the fallback lock-screen activity so both paths
 * behave identically. Everything it needs is passed in explicitly rather than
 * read from a host activity, which is what lets the same composable run inside a
 * bare [android.view.WindowManager] window with no activity behind it.
 */
@Composable
fun LockGate(
    packageName: String,
    appLabel: String,
    attemptLimiter: AttemptLimiter,
    onUnlocked: (String) -> Unit,
    onUseBiometrics: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var pin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var isBusy by remember { mutableStateOf(false) }

    // A one-second heartbeat, purely to drive recomposition.
    //
    // AttemptLimiter is plain in-memory state with nothing to observe it, so a
    // cool-off that is counting down would never cause a recomposition and the
    // time remaining would sit frozen at whatever it read when the lock-out
    // began — telling someone to wait 0:30 for the whole half minute. Recomputing
    // the remaining time on a key that changes is what makes it count down. The
    // lock screen is small and its composition is discarded the moment the overlay
    // hides, so a 1 Hz recomposition while a lock is on screen costs nothing.
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1_000)
            tick++
        }
    }

    fun submit() {
        if (isBusy) return

        // Nothing to say here: while a cool-off is running the message shown is
        // derived from the limiter on every recomposition, so the user already
        // sees the countdown and the reason it is not accepting input.
        if (attemptLimiter.isLockedOut) return

        isBusy = true
        scope.launch {
            // PBKDF2 at 120k iterations is intentionally slow, so it must not run
            // on the main thread or the keypad drops frames while it works.
            val correct = withContext(Dispatchers.Default) {
                LockyApp.from(context).pinManager.verify(pin)
            }
            isBusy = false

            if (correct) {
                attemptLimiter.onSuccess()
                onUnlocked(packageName)
            } else {
                attemptLimiter.onFailure()
                pin = ""
                error = context.getString(R.string.pin_wrong)
            }
        }
    }

    // Auto-submit once the PIN reaches a plausible length, which is what people
    // expect from a four-to-eight digit code.
    LaunchedEffect(pin) {
        if (pin.length >= MIN_PIN_LENGTH && !isBusy) {
            delay(AUTO_SUBMIT_DELAY_MILLIS)
            if (pin.length >= MIN_PIN_LENGTH) submit()
        }
    }

    // Clear the message that caused the lock-out once it expires. Without this the
    // "wrong PIN" text the user was last shown would reappear the moment the
    // cool-out ended, implying they had just tried again.
    //
    // Edged rather than level-triggered: clearing on every tick where no lock-out
    // is running would wipe the "wrong PIN" message a second after it appeared.
    var wasLockedOut by remember { mutableStateOf(false) }
    LaunchedEffect(tick) {
        if (attemptLimiter.isLockedOut) {
            wasLockedOut = true
        } else if (wasLockedOut) {
            wasLockedOut = false
            error = null
        }
    }

    // Keyed on the heartbeat so the value is re-read as the cool-off runs down.
    val lockoutRemaining: Long = remember(tick) { attemptLimiter.lockoutRemainingMillis }

    // The cool-off message is built here rather than stored in `error`, so the
    // time left is read on every recomposition. Storing it would freeze the text
    // at the value it had when the lock-out started.
    val shownError = if (attemptLimiter.isLockedOut) {
        context.getString(R.string.pin_locked_out, formatCountdown(lockoutRemaining))
    } else {
        error
    }

    PinEntryScreen(
        appLabel = appLabel,
        pin = pin,
        errorMessage = shownError,
        isBusy = isBusy,
        remainingAttempts = attemptLimiter.remainingAttempts,
        isLockedOut = attemptLimiter.isLockedOut,
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
            onClick = onSubmit,
            enabled = !isBusy && pin.length >= MIN_PIN_LENGTH && !isLockedOut,
        ) {
            Text(stringResource(R.string.action_unlock))
        }

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

private fun formatCountdown(millis: Long): String {
    val totalSeconds = (millis / 1000).coerceAtLeast(1)
    return "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
}
