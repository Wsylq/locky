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
import com.locky.app.service.LockyRuntime

/**
 * Live view of whether the lock is actually working.
 *
 * This exists because every other signal in the app is a configuration setting,
 * and configuration is not the same as behaviour. Each permission can be granted,
 * the checklist can be fully ticked, and the lock can still be doing nothing —
 * which looks identical to a working app from the outside. Reading these values
 * tells you which half is broken:
 *
 * - Watcher not running → nothing is watching app launches at all
 * - Watcher running, nothing armed → nothing has been switched on in the list
 * - App launches at 0 → the watcher is alive but is not being told about opens
 * - App launches counting, still unlocked → the fault is in the lock window
 *
 * The two app counts are reported separately on purpose. One is the watcher's own
 * cache and the other is what this screen has in its list, read independently. If
 * they disagree, arming is working and only one of the two views is stale — a
 * distinction that a single combined number would hide.
 */
@Composable
fun DiagnosticsPanel(
    runtime: LockyRuntime.Status,
    protectedCountInUi: Int,
    testResult: String?,
    onTestOverlay: () -> Unit,
    onTestFullScreen: () -> Unit,
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
                    if (runtime.isServiceConnected) {
                        R.string.diag_running
                    } else {
                        R.string.diag_stopped
                    },
                ),
                isGood = runtime.isServiceConnected,
            )

            StatusLine(
                label = stringResource(R.string.diag_protected),
                value = runtime.protectedAppCount.toString(),
                isGood = runtime.protectedAppCount > 0,
            )

            StatusLine(
                label = stringResource(R.string.diag_protected_in_ui),
                value = protectedCountInUi.toString(),
                isGood = protectedCountInUi > 0,
            )

            StatusLine(
                label = stringResource(R.string.diag_events),
                value = runtime.foregroundEventCount.toString(),
                isGood = runtime.foregroundEventCount > 0,
            )

            StatusLine(
                label = stringResource(R.string.diag_overlay),
                value = stringResource(
                    if (runtime.isOverlayAttached) {
                        R.string.diag_ready
                    } else {
                        R.string.diag_unavailable
                    },
                ),
                // Not a failure on its own: without the overlay permission the
                // app falls back to a full screen, which still locks.
                isGood = true,
            )

            if (runtime.watcherError != null) {
                Text(
                    text = stringResource(
                        R.string.diag_watcher_error,
                        runtime.watcherError,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            if (runtime.lastForegroundPackage != null) {
                Text(
                    text = stringResource(
                        R.string.diag_last_seen,
                        runtime.lastForegroundPackage,
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Text(
                text = stringResource(R.string.diag_tests_help),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.size(4.dp))

            // Both reach the same composable by different routes: one through the
            // overlay window, one through an activity. Neither involves detecting
            // an app, so what they render says nothing about detection and that is
            // the point — it tells the two faults apart without any logcat.
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = onTestOverlay,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(
                        text = stringResource(R.string.diag_test_overlay),
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
                OutlinedButton(
                    onClick = onTestFullScreen,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(
                        text = stringResource(R.string.diag_test_full_screen),
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
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
