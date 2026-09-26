package com.locky.app.ui

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.locky.app.LockyApp
import com.locky.app.admin.LockyAdminReceiver
import com.locky.app.data.AppRepository
import com.locky.app.data.InstalledApp
import com.locky.app.data.InstalledAppsLoader
import com.locky.app.security.BiometricUnlock
import com.locky.app.service.AppWatcherService
import com.locky.app.service.LockyRuntime
import com.locky.app.service.UnlockState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Where setup currently stands, so the UI can lead the user through it. */
data class SetupState(
    val isOverlayGranted: Boolean = false,
    val isDeviceAdminGranted: Boolean = false,
    val isAccessibilityGranted: Boolean = false,
    val isPinSet: Boolean = false,
) {
    /**
     * Setup is done once nothing is left to grant.
     *
     * The overlay permission is deliberately not required: without it Locky falls
     * back to the activity-based gate, which is slower but still locks apps.
     */
    val isComplete: Boolean
        get() = isDeviceAdminGranted && isAccessibilityGranted && isPinSet
}

/** Everything the apps screen renders, in one immutable value. */
data class AppsUiState(
    val isLoading: Boolean = true,
    val installed: List<InstalledApp> = emptyList(),
    val lockedPackages: Set<String> = emptySet(),
    val query: String = "",
    val errorMessage: String? = null,
) {
    val visible: List<InstalledApp>
        get() = if (query.isBlank()) {
            installed
        } else {
            installed.filter { it.label.contains(query, ignoreCase = true) }
        }

    val lockedCount: Int
        get() = installed.count { it.packageName in lockedPackages }
}

/**
 * State holder for the main screen.
 *
 * Reads the protected-app set from Room and the installed-app list from the
 * package manager, then combines the two into a single value the UI can render
 * without knowing where either came from.
 */
class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = AppRepository.get(application)
    private val loader = InstalledAppsLoader(application)

    private val installed = MutableStateFlow<List<InstalledApp>>(emptyList())
    private val isLoading = MutableStateFlow(true)
    private val errorMessage = MutableStateFlow<String?>(null)
    private val query = MutableStateFlow("")

    /** Bumped to force a re-read of grant state after returning from Settings. */
    private val setupTicker = MutableStateFlow(0)

    val setupState: StateFlow<SetupState> = setupTicker
        .map { readSetupState() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), readSetupState())

    val uiState: StateFlow<AppsUiState> =
        combine(
            combine(installed, isLoading, errorMessage) { apps, loading, error ->
                Triple(apps, loading, error)
            },
            repository.observeLockedApps(),
            query,
        ) { loaded, locked, search ->
            AppsUiState(
                isLoading = loaded.second,
                installed = loaded.first,
                lockedPackages = locked.mapTo(mutableSetOf()) { it.packageName },
                query = search,
                errorMessage = loaded.third,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppsUiState())

    val isBiometricAvailable: Boolean
        get() = BiometricUnlock.isAvailable(getApplication())

    /**
     * Whether the watcher is actually running.
     *
     * Deliberately separate from [SetupState.isAccessibilityGranted]: that reads
     * the Settings record, which can say "enabled" while the service has been
     * killed and is not actually gating anything. This reports what the service
     * itself last told us.
     */
    val runtimeStatus: StateFlow<LockyRuntime.Status> = LockyRuntime.status

    init {
        viewModelScope.launch { loadInstalledApps() }
    }

    /** Re-read grants and the app list; call after returning from Settings. */
    fun refresh() {
        setupTicker.value += 1
        viewModelScope.launch { loadInstalledApps() }
    }

    private suspend fun loadInstalledApps() {
        isLoading.value = true
        errorMessage.value = null
        try {
            installed.value = loader.load()
            // Rows can outlive the apps they describe, so reconcile on the way in.
            repository.pruneMissingApps(repository.getLockedApps())
        } catch (e: Exception) {
            errorMessage.value = e.message ?: e.javaClass.simpleName
        } finally {
            isLoading.value = false
        }
    }

    private fun readSetupState(): SetupState {
        val context = getApplication<Application>()
        return SetupState(
            isOverlayGranted = Settings.canDrawOverlays(context),
            isDeviceAdminGranted = LockyAdminReceiver.isAdminActive(context),
            isAccessibilityGranted = isAccessibilityServiceEnabled(context),
            isPinSet = LockyApp.from(context).pinManager.isPinSet,
        )
    }

    fun onQueryChange(value: String) {
        query.value = value
    }

    fun setLocked(packageName: String, locked: Boolean) {
        viewModelScope.launch { repository.setLocked(packageName, locked) }
    }

    fun setPin(pin: String) {
        LockyApp.from(getApplication()).pinManager.setPin(pin)
        setupTicker.value += 1
    }

    /** Ends every grace period, so the next protected app open asks again. */
    fun lockAllNow() {
        UnlockState.get(getApplication()).revokeAll()
    }

    // --- Intents into system settings -------------------------------------

    fun deviceAdminIntent(): Intent = LockyAdminReceiver.enableIntent(getApplication())

    fun accessibilityIntent(): Intent =
        Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /**
     * Intent that opens the "display over other apps" toggle.
     *
     * Unlike a runtime permission this cannot be requested inline: the user has
     * to flip the switch themselves, so the Settings screen is opened directly.
     */
    fun overlayPermissionIntent(): Intent {
        val context = getApplication<Application>()
        return Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.fromParts("package", context.packageName, null),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    companion object {
        /**
         * True when Locky's accessibility service is switched on.
         *
         * There is no public API for this, so the enabled-services setting is read
         * and matched against the service's flattened component name.
         */
        fun isAccessibilityServiceEnabled(context: Context): Boolean {
            val expected = context.packageName + "/" + AppWatcherService::class.java.name
            val enabled = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            ).orEmpty()

            return enabled.split(':').any { it.equals(expected, ignoreCase = true) }
        }
    }
}
