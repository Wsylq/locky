package com.locky.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.locky.app.R

/** Which half of the set-PIN flow is on screen. */
private enum class PinSetupStep { ENTER, CONFIRM }

/**
 * Dialog for choosing a new PIN.
 *
 * Entry and confirmation are separate passes, and the first entry is held only in
 * memory, so a mistyped PIN is caught before it is ever hashed or stored.
 */
@Composable
fun PinSetupDialog(
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var step by remember { mutableStateOf(PinSetupStep.ENTER) }
    var firstEntry by remember { mutableStateOf("") }
    var pin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    // Resolved here rather than inside submit(): stringResource is @Composable,
    // and submit() is an ordinary function.
    val tooShortMessage = stringResource(R.string.pin_too_short)
    val mismatchMessage = stringResource(R.string.pin_mismatch)

    fun submit() {
        if (pin.length < MIN_PIN_LENGTH) {
            error = tooShortMessage
            return
        }
        when (step) {
            PinSetupStep.ENTER -> {
                firstEntry = pin
                pin = ""
                error = null
                step = PinSetupStep.CONFIRM
            }

            PinSetupStep.CONFIRM -> {
                if (pin != firstEntry) {
                    error = mismatchMessage
                    pin = ""
                    return
                }
                onConfirm(firstEntry)
                onDismiss()
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = stringResource(
                    if (step == PinSetupStep.ENTER) {
                        R.string.setup_step_pin_title
                    } else {
                        R.string.pin_confirm_title
                    },
                ),
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                PinDots(length = pin.length, maxLength = MAX_PIN_LENGTH)

                Spacer(Modifier.height(12.dp))

                Text(
                    text = error.orEmpty(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center,
                )

                Spacer(Modifier.height(12.dp))

                PinKeypad(
                    onDigit = { digit ->
                        error = null
                        if (pin.length < MAX_PIN_LENGTH) pin += digit
                    },
                    onBackspace = {
                        error = null
                        if (pin.isNotEmpty()) pin = pin.dropLast(1)
                    },
                    modifier = Modifier.fillMaxWidth(0.95f),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = ::submit,
                enabled = pin.length >= MIN_PIN_LENGTH,
            ) {
                Text(
                    stringResource(
                        if (step == PinSetupStep.ENTER) {
                            R.string.action_continue
                        } else {
                            R.string.action_done
                        },
                    ),
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}
