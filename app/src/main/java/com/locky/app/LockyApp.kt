package com.locky.app

import android.app.Application
import android.content.Context
import com.locky.app.data.AppRepository
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
    }

    companion object {
        @Volatile
        private var instance: LockyApp? = null

        fun from(context: Context): LockyApp =
            context.applicationContext as LockyApp
    }
}
