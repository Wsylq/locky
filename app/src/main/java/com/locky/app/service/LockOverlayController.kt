package com.locky.app.service

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.locky.app.R
import com.locky.app.security.AttemptLimiter
import com.locky.app.ui.LockGate

/** What the overlay is currently gating. Null means it is not showing. */
data class OverlayTarget(
    val packageName: String,
    val appLabel: String,
)

/**
 * A persistent lock overlay drawn on top of whatever app is in the foreground.
 *
 * The window is added to the [WindowManager] once, when the accessibility service
 * connects, and then only ever toggled between `INVISIBLE` and `VISIBLE`. That
 * matters: the view tree is already measured, laid out and attached, and the
 * composition is already running, so [show] costs a single traversal instead of
 * the several hundred milliseconds an activity launch would take. This is the
 * difference between the user seeing the app flash and not seeing it at all.
 *
 * The window is `TYPE_APPLICATION_OVERLAY` and is deliberately *not* flagged
 * `NOT_FOCUSABLE`. An opaque, focusable window above the app means keystrokes and
 * touches go to the lock screen rather than through it to the app underneath.
 */
class LockOverlayController(private val context: Context) {

    private val windowManager = context.getSystemService(WindowManager::class.java)

    private var root: FrameLayout? = null
    private var lifecycleOwner: OverlayLifecycleOwner? = null

    /**
     * The app currently being gated. Drives the overlay's content, and lives
     * here rather than in the composable so [show] never has to rebuild a
     * composition.
     */
    private var target: OverlayTarget? by mutableStateOf(null)

    /**
     * Failed PIN attempts, deliberately shared across shows and across the
     * fallback activity, so the lock-out cannot be reset by simply leaving and
     * reopening the gated app.
     */
    val attemptLimiter = AttemptLimiter.shared()

    val isShowing: Boolean
        get() = root?.visibility == View.VISIBLE

    /**
     * True when the overlay window is attached and usable.
     *
     * False either because the overlay permission was refused or because adding
     * the window failed; callers fall back to the activity-based gate.
     */
    val isAttached: Boolean
        get() = root != null

    /** True when the user has granted the "display over other apps" permission. */
    fun canShow(): Boolean = Settings.canDrawOverlays(context)

    /**
     * Builds the window and its composition up front.
     *
     * Safe to call repeatedly. Does nothing if the overlay permission is missing
     * or the window cannot be added, leaving [isShowing] false so the caller can
     * fall back.
     */
    @SuppressLint("InflateParams")
    fun attach() {
        if (root != null) return
        if (!canShow()) {
            Log.w(TAG, "attach: overlay permission not granted, using activity gate")
            return
        }

        val owner = OverlayLifecycleOwner().also { it.onCreate() }
        val container = FrameLayout(context).apply {
            // Opaque from the moment it is added, so showing it never composites
            // the app underneath through a translucent frame.
            setBackgroundColor(context.getColor(R.color.lock_background))
            isFocusable = true
            isFocusableInTouchMode = true
            visibility = View.INVISIBLE
        }

        val composeView = ComposeView(context)
        // All three owners must be set on the ComposeView itself, before
        // setContent. A view added straight to the WindowManager has no activity
        // behind it, and Compose throws "ViewTreeViewModelStoreOwner not found"
        // when any of the three is unreachable. Setting them on the container is
        // not enough: setContent() runs before the view is added to that
        // container, so a lookup walking up the tree would find nothing there yet.
        composeView.setViewTreeLifecycleOwner(owner)
        composeView.setViewTreeViewModelStoreOwner(owner)
        composeView.setViewTreeSavedStateRegistryOwner(owner)
        composeView.setViewCompositionStrategy(
            ViewCompositionStrategy.DisposeOnDetachedFromWindowOrReleasedFromPool,
        )
        composeView.setContent { OverlayContent() }

        container.addView(
            composeView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )

        root = container
        lifecycleOwner = owner

        try {
            windowManager.addView(container, layoutParams())
            Log.i(TAG, "attach: overlay window added")
        } catch (e: Exception) {
            // Thrown if the permission was revoked between the check and the add,
            // or if the window token is invalid. Tear down so a later attach can
            // retry rather than leaving a half-initialised window.
            Log.e(TAG, "attach: addView failed, using activity gate", e)
            root = null
            owner.onDestroy()
            lifecycleOwner = null
        }
    }

    /** Reveals the lock over [packageName], updating it if already showing. */
    fun show(packageName: String, appLabel: String) {
        val container = root ?: run {
            Log.w(TAG, "show: no window attached, ignoring $packageName")
            return
        }
        target = OverlayTarget(packageName, appLabel)
        if (container.visibility != View.VISIBLE) {
            container.visibility = View.VISIBLE
        }
        // The overlay is focusable, so it must hold input focus for the keypad to
        // receive key events.
        container.requestFocus()
        Log.i(TAG, "show: gating $appLabel")
    }

    /** Hides the overlay. Does not end any grace period already granted. */
    fun hide() {
        target = null
        root?.visibility = View.INVISIBLE
    }

    /** Removes the window entirely. Called when the service goes away. */
    fun detach() {
        val container = root
        root = null
        target = null
        lifecycleOwner?.onDestroy()
        lifecycleOwner = null
        if (container != null) {
            runCatching { windowManager.removeViewImmediate(container) }
        }
    }

    private fun layoutParams() = WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED or
            // Keeps the PIN and the gated app out of screenshots.
            WindowManager.LayoutParams.FLAG_SECURE,
        PixelFormat.OPAQUE,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
    }

    @Composable
    private fun OverlayContent() {
        val current = target
        if (current == null) {
            // Nothing to gate. The window stays attached but invisible, keeping
            // the view tree and composition warm for the next launch.
            return
        }

        LockGate(
            packageName = current.packageName,
            appLabel = current.appLabel,
            attemptLimiter = attemptLimiter,
            onUnlocked = { packageName ->
                UnlockState.get(context).grant(packageName)
                AppWatcherService.onGateSatisfied(packageName)
                AppWatcherService.releaseChallenge()
                hide()
            },
            onUseBiometrics = {
                BiometricHostActivity.launch(
                    context = context,
                    packageName = current.packageName,
                    appLabel = current.appLabel,
                )
            },
        )
    }

    private companion object {
        const val TAG = "LockyOverlay"
    }
}

/**
 * Supplies the lifecycle and saved-state owners a [ComposeView] expects.
 *
 * A view added directly to the WindowManager has neither, and Compose throws
 * without them. This is the minimum shim that makes Compose work in a bare
 * window.
 */
private class OverlayLifecycleOwner :
    LifecycleOwner,
    ViewModelStoreOwner,
    SavedStateRegistryOwner {

    private val registry = LifecycleRegistry(this)
    private val store = ViewModelStore()
    private val savedState = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle get() = registry
    override val viewModelStore: ViewModelStore get() = store
    override val savedStateRegistry: SavedStateRegistry get() = savedState.savedStateRegistry

    fun onCreate() {
        savedState.performRestore(null)
        registry.currentState = Lifecycle.State.RESUMED
    }

    fun onDestroy() {
        registry.currentState = Lifecycle.State.DESTROYED
        store.clear()
    }
}
