package com.locky.app

import android.app.Application
import android.content.Context
import com.locky.app.data.AppRepository
import com.locky.app.security.BiometricPreference
import com.locky.app.security.PinManager

/**
 * Process-wide singletons. There is exactly one database, one PIN store and one
 * app list per process, so they are held here rather than being threaded through
 * every constructor.
 */
class LockyApp : Application() {

    val pinManager: PinManager by lazy { PinManager(this) }

    val repository: AppRepository by lazy { AppRepository(this) }

    override fun onCreate() {
        super.onCreate()
        instance = this
        // Seed the observable copy of the weak-biometric preference at process start.
        // The lock gate may be composed long before the settings screen is ever
        // opened, and it keys its own "can this phone do biometrics" check on this
        // value — an unseeded mirror would report the default rather than the stored
        // choice for the first lock the user sees.
        BiometricPreference.allowsWeakBiometrics(this)
    }

    companion object {
        @Volatile
        private var instance: LockyApp? = null

        fun from(context: Context): LockyApp =
            context.applicationContext as LockyApp
    }
}
