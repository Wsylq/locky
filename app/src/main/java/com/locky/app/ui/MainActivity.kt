package com.locky.app.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.locky.app.R
import com.locky.app.ui.theme.LockyTheme

/**
 * The app's only screen: pick which apps require the PIN.
 *
 * Extends [FragmentActivity] rather than `ComponentActivity` so the platform
 * biometric prompt can be hosted from here as well as from the lock screen.
 */
class MainActivity : FragmentActivity() {

    private val viewModel: MainViewModel by lazy {
        ViewModelProvider(this)[MainViewModel::class.java]
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            LockyTheme {
                MainScreen(
                    viewModel = viewModel,
                    onRequestOverlay = { startSafely(viewModel.overlayPermissionIntent()) },
                    onRequestDeviceAdmin = { startSafely(viewModel.deviceAdminIntent()) },
                    onRequestAccessibility = {
                        startFirst(viewModel.accessibilitySettingsIntents())
                    },
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Grants are toggled in system Settings, so returning here is the only
        // reliable moment to re-read them.
        viewModel.refresh()
    }

    private fun startSafely(intent: Intent) {
        runCatching { startActivity(intent) }
    }

    /**
     * Starts the first intent that resolves, so callers can offer a preferred
     * screen without having to know in advance which devices support it.
     */
    private fun startFirst(intents: List<Intent>) {
        for (intent in intents) {
            if (runCatching { startActivity(intent) }.isSuccess) return
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainScreen(
    viewModel: MainViewModel,
    onRequestOverlay: () -> Unit,
    onRequestDeviceAdmin: () -> Unit,
    onRequestAccessibility: () -> Unit,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val setupState by viewModel.setupState.collectAsStateWithLifecycle()
    val warning by viewModel.protectionWarning.collectAsStateWithLifecycle()
    val runtime by viewModel.runtimeStatus.collectAsStateWithLifecycle()
    val protectedCountInUi by viewModel.protectedCountInUi.collectAsStateWithLifecycle()
    val testResult by viewModel.testResult.collectAsStateWithLifecycle()
    var showPinSetup by remember { mutableStateOf(false) }

    // The checklist stays visible when setup is finished but the service is not
    // running, because that is when the user most needs to be told something is
    // wrong and every step is already ticked.
    val showChecklist = !setupState.isComplete || warning != null

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(stringResource(R.string.apps_title))
                        Text(
                            text = stringResource(
                                R.string.apps_locked_count,
                                uiState.lockedCount,
                                uiState.installed.size,
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                actions = {
                    IconButton(onClick = viewModel::lockAllNow) {
                        Icon(
                            imageVector = LockyIcons.LockNow,
                            contentDescription = stringResource(R.string.action_lock_now),
                        )
                    }
                    IconButton(onClick = { showPinSetup = true }) {
                        Icon(
                            imageVector = LockyIcons.ChangePin,
                            contentDescription = stringResource(R.string.action_change_pin),
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            OutlinedTextField(
                value = uiState.query,
                onValueChange = viewModel::onQueryChange,
                label = { Text(stringResource(R.string.apps_search_hint)) },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp),
            )

            if (showChecklist) {
                SetupChecklist(
                    state = setupState,
                    warning = warning,
                    onGrantOverlay = onRequestOverlay,
                    onGrantDeviceAdmin = onRequestDeviceAdmin,
                    onGrantAccessibility = onRequestAccessibility,
                    onSetPin = { showPinSetup = true },
                )
            }

            DiagnosticsPanel(
                runtime = runtime,
                protectedCountInUi = protectedCountInUi,
                testResult = testResult,
                onTestOverlay = viewModel::testOverlay,
                onTestFullScreen = viewModel::testFullScreenLock,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )

            AppList(
                state = uiState,
                onToggle = viewModel::setLocked,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            )
        }
    }

    if (showPinSetup) {
        PinSetupDialog(
            onDismiss = { showPinSetup = false },
            onConfirm = viewModel::setPin,
        )
    }
}
