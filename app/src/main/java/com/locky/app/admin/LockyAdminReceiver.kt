package com.locky.app.admin

import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.Toast
import com.locky.app.R
import com.locky.app.ui.MainActivity

/**
 * Device admin receiver.
 *
 * Locky uses device admin for exactly one thing: to keep itself from being
 * uninstalled while the lock is on. No `<uses-policies>` are declared, so this
 * grants no power over the device — it only makes deactivation require an
 * explicit confirmation.
 */
class LockyAdminReceiver : DeviceAdminReceiver() {

    /**
     * Called when the user tries to deactivate Locky. The default flow would let
     * them remove it silently, which defeats the point, so the system is asked to
     * show an explanation first.
     */
    override fun onDisableRequested(context: Context, intent: Intent): CharSequence =
        context.getString(R.string.device_admin_description)

    override fun onEnabled(context: Context, intent: Intent) {
        // Returning to the app keeps setup coherent: the UI watches the admin
        // state and can move on to the next step on its own.
        context.startActivity(
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    override fun onDisabled(context: Context, intent: Intent) {
        Toast.makeText(
            context,
            R.string.protection_inactive,
            Toast.LENGTH_LONG,
        ).show()
    }

    companion object {
        fun isAdminActive(context: Context): Boolean {
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE)
                    as? DevicePolicyManager
            return dpm?.isAdminActive(componentName(context)) == true
        }

        private fun componentName(context: Context): ComponentName =
            ComponentName(context, LockyAdminReceiver::class.java)

        /**
         * Intent that opens the system prompt to grant device admin.
         *
         * Both constants live on [DevicePolicyManager]. Inherited statics are not
         * in scope inside a companion object, so they must be qualified.
         */
        fun enableIntent(context: Context): Intent =
            Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).putExtra(
                DevicePolicyManager.EXTRA_DEVICE_ADMIN,
                componentName(context),
            )
    }
}
