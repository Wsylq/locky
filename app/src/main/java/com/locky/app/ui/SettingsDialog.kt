package com.locky.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.locky.app.R
import com.locky.app.security.ReLockOption

/**
 * Everything the user can change about how unlocking behaves, in one place.
 *
 * A dialog rather than inline controls because these are settings changed once and
 * then forgotten, and the app list needs the screen space more than a permanently
 * visible column of it.
 *
 * The two settings are not equally weighted and are not presented as though they
 * were. Re-lock timing is a preference, so it gets a list of values. Accepting a
 * Class 2 biometric is a reduction in what the lock is worth, so it gets a switch
 * and a sentence saying plainly what it gives up.
 */
@Composable
fun SettingsDialog(
    relock: ReLockOption,
    onRelock: (ReLockOption) -> Unit,
    biometrics: BiometricUiState,
    onAllowWeakBiometricsChange: (Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_title)) },
        text = {
            // Capped and scrollable: the re-lock options are a tall list and the
            // biometrics section adds to them, so on a small screen the dialog
            // would otherwise push its own buttons off the bottom edge.
            Column(
                modifier = Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                SectionHeading(stringResource(R.string.settings_lock_after))

                ReLockOption.entries.forEach { option ->
                    ReLockRow(
                        option = option,
                        isSelected = option == relock,
                        onSelect = { onRelock(option) },
                    )
                }

                Spacer(Modifier.height(16.dp))

                SectionHeading(stringResource(R.string.settings_biometrics))

                BiometricSummary(biometrics)

                // Only offered where it would actually change anything. A phone
                // whose face is already Class 3 is unlocked by face today, and
                // showing a switch that does nothing would imply otherwise.
                if (biometrics.needsWeakDecision) {
                    WeakBiometricSwitch(
                        checked = biometrics.allowsWeak,
                        onCheckedChange = onAllowWeakBiometricsChange,
                    )
                } else {
                    Text(
                        text = stringResource(
                            if (biometrics.capabilities.strongBiometrics) {
                                R.string.settings_face_already_strong
                            } else {
                                R.string.settings_biometric_none
                            },
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_done))
            }
        },
    )
}

/**
 * What is actually enrolled on this phone, read from the platform.
 *
 * Included because "face unlock did not appear" has two very different causes —
 * the sensor is Class 2, or nothing is enrolled — and they need different fixes.
 * The answer is stated rather than left for the user to infer from a prompt that
 * quietly never shows up.
 */
@Composable
private fun BiometricSummary(biometrics: BiometricUiState) {
    val enrolled = buildList {
        if (biometrics.capabilities.strongBiometrics) {
            add(stringResource(R.string.diag_bio_class_strong))
        }
        if (biometrics.capabilities.weakBiometrics) {
            add(stringResource(R.string.diag_bio_class_weak))
        }
        if (biometrics.capabilities.deviceCredential) {
            add(stringResource(R.string.diag_bio_device_credential))
        }
    }

    Text(
        text = stringResource(R.string.diag_biometrics) + ": " + (
            if (enrolled.isEmpty()) {
                stringResource(R.string.diag_none)
            } else {
                enrolled.joinToString(", ")
            }
            ),
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.padding(bottom = 8.dp),
    )
}

/**
 * The one control in the app that lowers security, so it carries its consequence
 * next to it rather than behind a help link.
 */
@Composable
private fun WeakBiometricSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Column {
        Row(
            // toggleable on the row so the whole line is the target and screen
            // readers announce one switch rather than a switch and some text.
            modifier = Modifier
                .fillMaxWidth()
                .toggleable(
                    value = checked,
                    onValueChange = onCheckedChange,
                    role = Role.Switch,
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.settings_allow_weak_biometrics),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f),
            )
            Switch(checked = checked, onCheckedChange = null)
        }

        Text(
            text = if (checked) {
                stringResource(R.string.settings_weak_on_notice)
            } else {
                stringResource(R.string.settings_allow_weak_summary)
            },
            style = MaterialTheme.typography.bodyMedium,
            color = if (checked) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
    }
}

@Composable
private fun SectionHeading(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
    )
    Spacer(Modifier.height(6.dp))
}

@Composable
private fun ReLockRow(
    option: ReLockOption,
    isSelected: Boolean,
    onSelect: () -> Unit,
) {
    Row(
        // selectable on the row rather than the radio alone, so the whole line is a
        // comfortable tap target and screen readers announce it as one choice.
        modifier = Modifier
            .fillMaxWidth()
            .selectable(
                selected = isSelected,
                onClick = onSelect,
                role = Role.RadioButton,
            )
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = isSelected, onClick = null)
        Text(
            text = stringResource(option.labelRes),
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(start = 8.dp),
        )
    }
}
