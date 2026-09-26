package com.locky.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.locky.app.R
import com.locky.app.service.LockyRuntime

/**
 * Setup status, collapsed to a single row once there is nothing to do.
 *
 * The four step cards are tall — together they are taller than most phone
 * screens. Because the app list below this takes whatever space is left, an
 * expanded checklist left the list with nothing at all, so the user could not
 * scroll to the apps they meant to lock. Two things prevent that here: the cards
 * collapse to one line when setup is finished, and the expanded card list is
 * hard-capped in height and scrolls inside itself.
 */
@Composable
fun SetupChecklist(
    state: SetupState,
    warning: LockyRuntime.Problem?,
    onGrantOverlay: () -> Unit,
    onGrantDeviceAdmin: () -> Unit,
    onGrantAccessibility: () -> Unit,
    onSetPin: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Keyed on isComplete, so the moment the last grant lands the whole block
    // folds itself away instead of lingering over the app list.
    var isExpanded by remember(state.isComplete) { mutableStateOf(!state.isComplete) }

    val hasSomethingToSay = !state.isComplete || warning != null

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        StatusRow(
            text = when {
                warning != null -> stringResource(R.string.warning_not_protecting_short)
                state.isComplete -> stringResource(R.string.protection_active)
                else -> stringResource(R.string.protection_inactive)
            },
            isHealthy = !hasSomethingToSay,
            isExpanded = isExpanded,
            onToggle = { isExpanded = !isExpanded },
        )

        // Kept outside the collapse: this is the one thing the user must not have
        // to tap through to see.
        warning?.let { problem ->
            RuntimeWarning(problem = problem, onFix = onGrantAccessibility)
        }

        AnimatedVisibility(visible = isExpanded && hasSomethingToSay) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    // The hard guarantee: however tall the steps get, the app
                    // list below always keeps enough room to scroll.
                    .heightIn(max = 280.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
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
    }
}

/** One-line summary that also acts as the expand/collapse control. */
@Composable
private fun StatusRow(
    text: String,
    isHealthy: Boolean,
    isExpanded: Boolean,
    onToggle: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = !isHealthy, onClick = onToggle)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = if (isHealthy) Icons.Filled.CheckCircle else Icons.Filled.Warning,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = if (isHealthy) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.error
            },
        )

        Spacer(Modifier.width(8.dp))

        Text(
            text = text,
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.weight(1f),
        )

        if (!isHealthy) {
            Icon(
                imageVector = if (isExpanded) {
                    Icons.Filled.KeyboardArrowUp
                } else {
                    Icons.Filled.KeyboardArrowDown
                },
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Banner explaining that Locky is installed and configured but not actually
 * locking anything.
 *
 * The action offered matches the problem. An earlier version showed "Re-enable
 * Locky" for every cause, which sent users to Settings to fix something that was
 * not a settings problem.
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
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = stringResource(R.string.warning_not_protecting_title),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Spacer(Modifier.size(4.dp))
            Text(
                text = when (problem) {
                    LockyRuntime.Problem.SERVICE_STOPPED ->
                        stringResource(R.string.warning_service_stopped)

                    LockyRuntime.Problem.NO_PROTECTED_APPS ->
                        stringResource(R.string.warning_no_protected_apps)

                    LockyRuntime.Problem.WATCHER_FAILED ->
                        stringResource(R.string.warning_watcher_failed)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )

            // The service is listed under its own label in Settings, not under
            // the app name, which is the single most likely reason someone cannot
            // find it when told to go re-enable "Locky".
            if (problem == LockyRuntime.Problem.SERVICE_STOPPED) {
                Spacer(Modifier.size(6.dp))
                Text(
                    text = stringResource(
                        R.string.warning_find_service,
                        stringResource(R.string.accessibility_service_label),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }

            // Only the stopped-service case is fixed in Settings. Telling someone
            // to go re-enable an already-running service is worse than saying
            // nothing at all.
            if (problem == LockyRuntime.Problem.SERVICE_STOPPED) {
                Spacer(Modifier.size(8.dp))
                Text(
                    text = stringResource(R.string.warning_find_service),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
                Spacer(Modifier.size(8.dp))
                OutlinedButton(onClick = onFix) {
                    Text(stringResource(R.string.action_reenable_service))
                }
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
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = !isDone, onClick = onClick)
                .padding(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                )
                if (isDone) {
                    Spacer(Modifier.width(8.dp))
                    Icon(
                        imageVector = Icons.Filled.Check,
                        contentDescription = stringResource(R.string.action_enabled),
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }

            Spacer(Modifier.size(4.dp))

            Text(
                text = body,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            AnimatedVisibility(visible = !isDone) {
                OutlinedButton(
                    onClick = onClick,
                    modifier = Modifier.padding(top = 8.dp),
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
