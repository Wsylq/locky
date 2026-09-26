package com.locky.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.locky.app.R

/**
 * Live view of whether the lock is actually working.
 *
 * This exists because every other signal in the app is a configuration setting,
 * and configuration is not the same as behaviour. Each permission can be granted,
 * the checklist can be fully ticked, and the lock can still be doing nothing —
 * which looks identical to a working app from the outside. Reading these four
 * values tells you which half is broken:
 *
 * - Service not running  → nothing is watching app launches at all
 * - Service running, 0 protected apps → nothing has been armed
 * - Events at 0 → the service is alive but is not receiving app launches
 * - Events counting, still unlocked → the failure is in the lock window itself
 */
@Composable
fun DiagnosticsPanel(
    diagnostics: Diagnostics,
    testResult: String?,
    onTest: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = stringResource(R.string.diag_title),
                style = MaterialTheme.typography.titleSmall,
            )

            StatusLine(
                label = stringResource(R.string.diag_service),
                value = stringResource(
                    if (diagnostics.isServiceConnected) {
                        R.string.diag_running
                    } else {
                        R.string.diag_stopped
                    },
                ),
                isGood = diagnostics.isServiceConnected,
            )

            StatusLine(
                label = stringResource(R.string.diag_protected),
                value = diagnostics.protectedAppCount.toString(),
                isGood = diagnostics.protectedAppCount > 0,
            )

            StatusLine(
                label = stringResource(R.string.diag_events),
                value = diagnostics.foregroundEventCount.toString(),
                isGood = diagnostics.foregroundEventCount > 0,
            )

            StatusLine(
                label = stringResource(R.string.diag_overlay),
                value = stringResource(
                    if (diagnostics.isOverlayAttached) {
                        R.string.diag_ready
                    } else {
                        R.string.diag_unavailable
                    },
                ),
                // Not a failure on its own: without the overlay permission the
                // app falls back to a full screen, which still locks.
                isGood = true,
            )

            if (diagnostics.lastForegroundPackage != null) {
                Text(
                    text = stringResource(
                        R.string.diag_last_seen,
                        diagnostics.lastForegroundPackage,
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.size(4.dp))

            OutlinedButton(onClick = onTest, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.diag_test_button))
            }

            if (testResult != null) {
                Text(
                    text = testResult,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun StatusLine(
    label: String,
    value: String,
    isGood: Boolean,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = if (isGood) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.error
            },
        )
    }
}
