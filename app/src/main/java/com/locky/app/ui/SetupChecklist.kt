package com.locky.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.locky.app.R
import com.locky.app.service.LockyRuntime

/**
 * The setup checklist.
 *
 * Each step stays on screen once completed rather than disappearing, so the user
 * can see at a glance that it is done and undo it from Settings if they change
 * their mind.
 */
@Composable
fun SetupChecklist(
    state: SetupState,
    runtime: LockyRuntime.Status,
    onGrantOverlay: () -> Unit,
    onGrantDeviceAdmin: () -> Unit,
    onGrantAccessibility: () -> Unit,
    onSetPin: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = if (state.isComplete) {
                stringResource(R.string.protection_active)
            } else {
                stringResource(R.string.protection_inactive)
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // Shown when every permission looks granted yet nothing is being locked.
        // Without this the failure is completely silent: the checklist is all
        // ticked, so the only symptom is that protected apps just open.
        runtime.problem?.let { problem ->
            RuntimeWarning(problem = problem, onFix = onGrantAccessibility)
        }

        SetupStep(
            title = stringResource(R.string.setup_step_overlay_title),
            body = stringResource(R.string.setup_step_overlay_body),
            isDone = state.isOverlayGranted,
            isOptional = true,
            onClick = onGrantOverlay,
        )

        SetupStep(
            title = stringResource(R.string.setup_step_admin_title),
            body = stringResource(R.string.setup_step_admin_body),
            isDone = state.isDeviceAdminGranted,
            onClick = onGrantDeviceAdmin,
        )

        SetupStep(
            title = stringResource(R.string.setup_step_accessibility_title),
            body = stringResource(R.string.setup_step_accessibility_body),
            isDone = state.isAccessibilityGranted,
            onClick = onGrantAccessibility,
        )

        SetupStep(
            title = stringResource(R.string.setup_step_pin_title),
            body = stringResource(R.string.setup_step_pin_body),
            isDone = state.isPinSet,
            onClick = onSetPin,
        )
    }
}

/**
 * Banner explaining that Locky is installed and configured but not actually
 * locking anything, with a button that goes straight to the relevant setting.
 */
@Composable
private fun RuntimeWarning(
    problem: LockyRuntime.Problem,
    onFix: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.warning_not_protecting_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Spacer(Modifier.size(4.dp))
            Text(
                text = when (problem) {
                    LockyRuntime.Problem.SERVICE_STOPPED ->
                        stringResource(R.string.warning_service_stopped)

                    LockyRuntime.Problem.NO_PROTECTED_APPS ->
                        stringResource(R.string.warning_no_protected_apps)
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Spacer(Modifier.size(8.dp))
            OutlinedButton(onClick = onFix) {
                Text(stringResource(R.string.action_reenable_service))
            }
        }
    }
}

@Composable
private fun SetupStep(
    title: String,
    body: String,
    isDone: Boolean,
    onClick: () -> Unit,
    isOptional: Boolean = false,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (isDone) {
                MaterialTheme.colorScheme.surfaceVariant
            } else {
                MaterialTheme.colorScheme.primaryContainer
            },
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = !isDone, onClick = onClick)
                .padding(16.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                    )
                    if (isDone) {
                        Spacer(Modifier.width(8.dp))
                        Icon(
                            imageVector = Icons.Filled.Check,
                            contentDescription = stringResource(R.string.action_enabled),
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                }

                Spacer(Modifier.size(4.dp))

                Text(
                    text = body,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                AnimatedVisibility(visible = !isDone) {
                    OutlinedButton(
                        onClick = onClick,
                        modifier = Modifier.padding(top = 12.dp),
                    ) {
                        Text(
                            stringResource(
                                if (isOptional) R.string.action_grant_optional
                                else R.string.action_grant,
                            ),
                        )
                    }
                }
            }
        }
    }
}
