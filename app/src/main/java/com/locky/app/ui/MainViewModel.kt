package com.locky.app.ui

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.locky.app.LockyApp
import com.locky.app.R
import com.locky.app.admin.LockyAdminReceiver
import com.locky.app.data.AppRepository
import com.locky.app.data.InstalledApp
import com.locky.app.data.InstalledAppsLoader
import com.locky.app.security.BiometricUnlock
import com.locky.app.security.ReLockOption
import com.locky.app.security.ReLockPolicy
import com.locky.app.service.AppWatcherService
import com.locky.app.service.LockScreenActivity
import com.locky.app.service.LockyRuntime
import com.locky.app.service.UnlockState
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.transformLatest
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

    /** Bumped when the re-lock period is changed, to republish [relockOption]. */
    private val relockTicker = MutableStateFlow(0)

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
     * Why Locky is not protecting anything, or null when it is.
     *
     * Deliberately not the raw service status. A freshly started process has not
     * been told the service connected yet, so reporting that as a problem would
     * flash a false alarm every time the app opened. A service that Settings says
     * is enabled but which has not bound after a grace period is a real problem,
     * and that is the case worth naming.
     */
    val protectionWarning: StateFlow<LockyRuntime.Problem?> =
        combine(setupState, LockyRuntime.status) { setup, runtime ->
            diagnose(setup, runtime)
        }
            .transformLatest { problem ->
                if (problem == LockyRuntime.Problem.SERVICE_STOPPED) {
                    // Android binds an enabled accessibility service shortly after
                    // the process starts, so wait rather than accuse immediately.
                    delay(SERVICE_BIND_GRACE_MILLIS)
                    if (LockyRuntime.status.value.isServiceConnected) return@transformLatest
                }
                emit(problem)
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private fun diagnose(
        setup: SetupState,
        runtime: LockyRuntime.Status,
    ): LockyRuntime.Problem? = when {
        // The one thing that must be nagged about. Android switches the service
        // off on every update and reinstall, and until it is back on nothing else
        // in the app matters.
        !setup.isAccessibilityGranted -> LockyRuntime.Problem.SERVICE_NOT_ENABLED

        runtime.isServiceConnected && runtime.protectedAppCount == 0 ->
            LockyRuntime.Problem.NO_PROTECTED_APPS

        !runtime.isServiceConnected -> LockyRuntime.Problem.SERVICE_STOPPED

        else -> null
    }

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
            val apps = loader.load()
            installed.value = apps
            // Rows can outlive the apps they describe, so reconcile on the way in.
            // Reusing the list just loaded rather than re-reading the package
            // manager for it a second time.
            repository.pruneMissingApps(apps.mapTo(mutableSetOf()) { it.packageName })
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

    /**
     * The chosen re-lock period, republished whenever it changes.
     *
     * The value lives in a preference file rather than in the view model so that
     * the watcher and this screen can never disagree about it, but the screen
     * still needs to be told when it changed, hence the tick.
     */
    val relockOption: StateFlow<ReLockOption> = relockTicker
        .map { ReLockPolicy.current(getApplication()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ReLockOption.DEFAULT)

    /**
     * Saves a new re-lock period and drops any grace period already running.
     *
     * Clearing the existing grants is what makes shortening the setting feel like
     * it worked: without it, an app unlocked a minute ago would keep its original
     * minute regardless of the user having just chosen "immediately".
     */
    fun setRelockOption(option: ReLockOption) {
        ReLockPolicy.set(getApplication(), option)
        UnlockState.get(getApplication()).revokeAll()
        relockTicker.value += 1
    }

    // --- Intents into system settings -------------------------------------

    fun deviceAdminIntent(): Intent = LockyAdminReceiver.enableIntent(getApplication())

    fun accessibilityIntent(): Intent =
        Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /**
     * Settings screens to try, best first, for switching the service back on.
     *
     * Android buries app accessibility services under Accessibility > Installed
     * services, and lists this one as "Locky app lock" rather than "Locky", so
     * users sent to the generic list routinely cannot find it and conclude
     * nothing is wrong. The per-service screen is tried first because it lands
     * directly on the switch.
     *
     * That action is undocumented and absent on some devices, so the generic
     * list is kept as a fallback rather than resolved up front — package
     * visibility rules on Android 11+ make `resolveActivity` unreliable for
     * Settings targets, and would silently discard the good intent.
     */
    fun accessibilitySettingsIntents(): List<Intent> {
        val context = getApplication<Application>()
        val component = ComponentName(context, AppWatcherService::class.java)
        return listOf(
            Intent("android.settings.ACCESSIBILITY_DETAILS_SETTINGS")
                .putExtra("android.intent.extra.COMPONENT_NAME", component)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            accessibilityIntent(),
        )
    }

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

    /**
     * The watcher's own view of the world, taken straight from the service.
     *
     * Deliberately not folded into a combined state with the database read. A
     * `combine` only emits once every source has produced a value, so mixing the
     * service status with the protected-app query meant a database that was slow
     * or stalled left the whole panel showing its initial defaults — which read
     * exactly like "nothing is running". Two independent sources cannot lie to
     * each other that way.
     */
    val runtimeStatus: StateFlow<LockyRuntime.Status> = LockyRuntime.status

    /**
     * How many apps this screen believes are locked.
     *
     * Compared against the watcher's own count: if the watcher sees more than the
     * list does, arming is working and only the display is stale, and vice versa.
     */
    val protectedCountInUi: StateFlow<Int> = repository.observeLockedApps()
        .map { it.size }
        .catch { emit(0) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    private val _testResult = MutableStateFlow<String?>(null)

    /** Result of the most recent test attempt, shown under the buttons. */
    val testResult: StateFlow<String?> = _testResult

    /**
     * Shows the lock screen over this very app.
     *
     * Separates the two halves of the feature: if the PIN screen appears here,
     * the overlay renders and the PIN works, so any remaining failure is in
     * detecting app launches. If nothing appears, the problem is the window
     * itself and nothing about detection is worth investigating.
     */
    fun testOverlay() {
        val context = getApplication<Application>()
        val overlay = AppWatcherService.overlay()
        if (overlay == null || !overlay.isAttached) {
            _testResult.value = context.getString(R.string.diag_test_no_service)
            return
        }
        _testResult.value = null
        overlay.show(context.packageName, context.getString(R.string.diag_test_app_name))
    }

    /**
     * Shows the fallback lock screen, bypassing the watcher entirely.
     *
     * The two self-tests are deliberately independent paths to the same [LockGate]
     * composable. Neither this one nor [testOverlay] touches app detection, so
     * together they bracket the problem: if this renders but a protected app opens
     * freely, every remaining fault is in detection; if neither renders, the lock
     * UI itself is at fault and detection is irrelevant.
     */
    fun testFullScreenLock() {
        val context = getApplication<Application>()
        val intent = Intent(context, LockScreenActivity::class.java)
            .putExtra(
                LockScreenActivity.EXTRA_PACKAGE_NAME,
                context.packageName,
            )
            .putExtra(
                LockScreenActivity.EXTRA_LABEL,
                context.getString(R.string.diag_test_app_name),
            )
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
            .onFailure { _testResult.value = it.message ?: it.javaClass.simpleName }
    }

    companion object {
        /**
         * How long to wait for the system to bind an enabled accessibility
         * service before reporting it as stopped.
         */
        private const val SERVICE_BIND_GRACE_MILLIS = 2_500L

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
