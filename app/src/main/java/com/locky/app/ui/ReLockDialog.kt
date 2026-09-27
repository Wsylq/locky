package com.locky.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
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
 * Chooses how long an app stays unlocked after a successful unlock.
 *
 * A dialog rather than an inline control because this is a setting changed once and
 * then forgotten, and the app list needs the screen space more than a permanently
 * visible row of it.
 */
@Composable
fun ReLockDialog(
    selected: ReLockOption,
    onSelect: (ReLockOption) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_lock_after)) },
        text = {
            // Capped and scrollable: the longest option ("1 hour") is much wider
            // than the shortest, and on a small screen the full list plus the
            // buttons would otherwise push the dialog past the screen edge.
            Column(
                modifier = Modifier
                    .heightIn(max = 360.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                ReLockOption.entries.forEach { option ->
                    ReLockRow(
                        option = option,
                        isSelected = option == selected,
                        onSelect = { onSelect(option) },
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
