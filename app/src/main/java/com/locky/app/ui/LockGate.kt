package com.locky.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.locky.app.LockyApp
import com.locky.app.R
import com.locky.app.security.AttemptLimiter
import com.locky.app.security.BiometricPreference
import com.locky.app.security.BiometricUnlock
import com.locky.app.ui.theme.AccentBlue
import com.locky.app.ui.theme.AccentGlow
import com.locky.app.ui.theme.AccentGlassFill
import com.locky.app.ui.theme.AccentOutline
import com.locky.app.ui.theme.DarkBackground
import com.locky.app.ui.theme.DarkOnSurfaceVariant
import com.locky.app.ui.theme.DarkSurface
import com.locky.app.ui.theme.EyebrowStyle
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

    // Whether the device has a secure screen lock to fall back on, which is what
    // makes the fingerprint or face prompt available at all.
    //
    // Keyed on the weak-biometric choice rather than cached once and forgotten: the
    // overlay builds this composable a single time and then shows and hides it, so a
    // plain remember would keep the answer from the first lock the user ever saw.
    // Turning on face unlock would then look like it had done nothing until the
    // process restarted.
    val allowsWeakBiometrics by BiometricPreference.allowsWeakFlow.collectAsState()
    val biometricsAvailable = remember(context, allowsWeakBiometrics) {
        BiometricUnlock.isAvailable(context)
    }

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

    // Offer biometrics straight away, once, when they are available.
    //
    // Someone who has already set a screen lock should not have to reach past a
    // keypad for a fingerprint prompt, so it is the default path rather than an
    // extra button. Keyed on the app being gated because the overlay reuses this
    // composition for each new app, and shown only once: if the user cancels or
    // fails, the keypad is right there and nothing re-triggers it.
    //
    // The cool-out check is load-bearing. A successful biometric grants a grace
    // period without consulting the limiter, so raising the prompt during a
    // lock-out would be a way straight past it — the button below is disabled in
    // the same situation for the same reason.
    LaunchedEffect(packageName, biometricsAvailable) {
        if (biometricsAvailable && !attemptLimiter.isLockedOut) onUseBiometrics()
    }

    // Auto-submit once the PIN is complete, which is what people expect from a
    // code rather than a password.
    //
    // Keyed on the PIN's real length, not on the shortest one the app accepts. The
    // old heuristic submitted as soon as four digits were in, so any PIN longer
    // than that was submitted truncated and came back wrong every single time — a
    // 120ms pause part-way through six digits was enough, which is a very natural
    // way to type. Five of those is a 30-second cool-off entered with a perfectly
    // correct PIN, and nothing about it looks like a mistyped code.
    //
    // Null length means the PIN predates this being recorded, and then nothing is
    // auto-submitted: the Unlock button is the safe direction to be wrong in.
    val expectedLength = remember(context) { LockyApp.from(context).pinManager.pinLength }
    LaunchedEffect(pin, expectedLength) {
        val complete = expectedLength
        if (complete != null && pin.length == complete && !isBusy) {
            delay(AUTO_SUBMIT_DELAY_MILLIS)
            if (pin.length == complete) submit()
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
        biometricsAvailable = biometricsAvailable,
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
    biometricsAvailable: Boolean,
    remainingAttempts: Int,
    isLockedOut: Boolean,
    onDigit: (Char) -> Unit,
    onBackspace: () -> Unit,
    onSubmit: () -> Unit,
    onUseBiometrics: () -> Unit,
) {
    // Matched to the hardware the phone has. On a face-only handset a fingerprint
    // glyph is a promise of a gesture the user cannot make.
    val unlockIcon = LockyIcons.unlockIcon(LocalContext.current)
    Box(
        modifier = Modifier
            .fillMaxSize()
            // A vertical wash rather than one flat fill. Lighter at the top where
            // the badge and the title sit, so the screen has a light source and
            // the eye lands on the app name first.
            .background(
                Brush.verticalGradient(
                    0f to DarkSurface,
                    0.6f to DarkBackground,
                    1f to DarkBackground,
                ),
            ),
    ) {
        // A single soft accent bloom behind the badge, fading to nothing. Carries
        // most of the "premium" impression and costs one draw: a flat dark screen
        // reads as an unfinished dark mode, this reads as a lit surface.
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .size(600.dp)
                .offset(y = (-240).dp)
                .background(
                    Brush.radialGradient(listOf(AccentGlow, Color.Transparent)),
                ),
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 28.dp, vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            // The lock in a tinted disc rather than floating on its own, so it
            // reads as a badge and not as a stray icon.
            Box(
                modifier = Modifier
                    .size(78.dp)
                    .clip(CircleShape)
                    .background(AccentGlassFill)
                    .border(1.dp, AccentOutline, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = LockyIcons.Lock,
                    contentDescription = null,
                    modifier = Modifier.size(30.dp),
                    tint = AccentBlue,
                )
            }

            Spacer(Modifier.height(26.dp))

            // Small, wide-tracked and quiet, with the app name given the weight
            // instead. "Enter your PIN" as a headline would be wrong here: the
            // fingerprint is the intended way in and the keypad is the fallback.
            Text(
                text = stringResource(R.string.lock_title).uppercase(),
                style = EyebrowStyle,
                color = AccentBlue,
            )

            Spacer(Modifier.height(8.dp))

            Text(
                text = appLabel,
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
            )

            // Biometrics first, above the dots: the primary way through this
            // screen. The keypad stays below it as the fallback for a failed or
            // cancelled prompt, and a cool-out.
            if (biometricsAvailable) {
                Spacer(Modifier.height(28.dp))

                Button(
                    onClick = onUseBiometrics,
                    enabled = !isBusy && !isLockedOut,
                    shape = RoundedCornerShape(18.dp),
                    border = BorderStroke(1.dp, AccentOutline),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = AccentGlassFill,
                        contentColor = AccentBlue,
                        disabledContainerColor = AccentGlassFill.copy(alpha = 0.3f),
                        disabledContentColor = DarkOnSurfaceVariant,
                    ),
                    contentPadding = PaddingValues(vertical = 14.dp),
                    modifier = Modifier.fillMaxWidth(0.86f),
                ) {
                    Icon(
                        imageVector = unlockIcon,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.size(10.dp))
                    Text(stringResource(R.string.action_use_biometrics))
                }
            }

            Spacer(Modifier.height(30.dp))

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

            Spacer(Modifier.height(18.dp))

            PinKeypad(
                onDigit = onDigit,
                onBackspace = onBackspace,
                modifier = Modifier.fillMaxWidth(0.92f),
            )

            Spacer(Modifier.height(14.dp))

            TextButton(
                onClick = onSubmit,
                enabled = !isBusy && pin.length >= MIN_PIN_LENGTH && !isLockedOut,
            ) {
                Text(stringResource(R.string.action_unlock))
            }
        }
    }
}

private fun formatCountdown(millis: Long): String {
    val totalSeconds = (millis / 1000).coerceAtLeast(1)
    return "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
}
