package com.locky.app.security

import android.content.Context
import androidx.annotation.StringRes
import com.locky.app.R

/**
 * How long an app stays unlocked after the PIN or biometric is accepted.
 *
 * Called the re-lock time because that is what the user is actually choosing: how
 * long they have to switch to another protected app and come back before being
 * asked again. Too short and every app switch prompts; too long and the lock is
 * barely doing anything. A minute is the default because it covers tidying up
 * mid-task without leaving an app open for long.
 *
 * The values are milliseconds rather than a stored enum name so that options can
 * be added, removed, or reworded in later versions without invalidating whatever
 * the user already chose.
 */
enum class ReLockOption(
    val millis: Long,
    @StringRes val labelRes: Int,
) {
    /** Ask every single time an app is opened, with no grace at all. */
    IMMEDIATELY(0L, R.string.relock_immediately),

    SECONDS_15(15_000L, R.string.relock_seconds_15),
    SECONDS_30(30_000L, R.string.relock_seconds_30),
    MINUTE_1(60_000L, R.string.relock_minute_1),
    MINUTES_5(5 * 60_000L, R.string.relock_minutes_5),
    MINUTES_15(15 * 60_000L, R.string.relock_minutes_15),
    MINUTES_30(30 * 60_000L, R.string.relock_minutes_30),
    HOUR_1(60 * 60_000L, R.string.relock_hour_1),

    ;

    companion object {
        /** The one used until the user picks something else. */
        val DEFAULT: ReLockOption = MINUTE_1
    }
}

/**
 * Stores the chosen [ReLockOption].
 *
 * Read on demand rather than cached, so a change takes effect for the watcher
 * immediately. The accessibility service is long-lived, and a value captured when
 * it connected would otherwise be stuck at the old setting until the user toggled
 * the service off and on again.
 */
object ReLockPolicy {

    private const val PREFS_NAME = "locky_settings"
    private const val KEY_RELOCK_MILLIS = "relock_millis"

    fun current(context: Context): ReLockOption {
        val stored = prefs(context).getLong(KEY_RELOCK_MILLIS, -1L)
        return ReLockOption.entries.firstOrNull { it.millis == stored } ?: ReLockOption.DEFAULT
    }

    fun set(context: Context, option: ReLockOption) {
        prefs(context).edit().putLong(KEY_RELOCK_MILLIS, option.millis).apply()
    }

    /** The grace period to grant, in milliseconds. */
    fun graceMillis(context: Context): Long = current(context).millis

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
