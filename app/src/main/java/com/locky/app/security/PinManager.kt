package com.locky.app.security

import android.content.Context
import android.content.SharedPreferences
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * Stores and verifies the Locky PIN.
 *
 * The PIN itself is never written to disk. Instead a random 16-byte salt and a
 * PBKDF2-HMAC-SHA256 hash are persisted, so reading the preferences file does not
 * reveal the PIN. Comparison uses a constant-time routine to avoid leaking the
 * hash through timing.
 */
class PinManager(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    val isPinSet: Boolean
        get() = prefs.contains(KEY_SALT) && prefs.contains(KEY_HASH)

    /** Stores [pin], replacing any previous value. */
    fun setPin(pin: String) {
        val salt = ByteArray(SALT_BYTES).also(SecureRandom()::nextBytes)
        val hash = derive(pin, salt)
        prefs.edit()
            .putString(KEY_SALT, salt.toHex())
            .putString(KEY_HASH, hash.toHex())
            .apply()
    }

    /** Returns true when [pin] matches the stored hash. */
    fun verify(pin: CharArray): Boolean {
        val salt = prefs.getString(KEY_SALT, null)?.fromHex() ?: return false
        val expected = prefs.getString(KEY_HASH, null)?.fromHex() ?: return false
        return MessageDigest.isEqual(expected, derive(pin, salt))
    }

    fun verify(pin: String): Boolean = verify(pin.toCharArray())

    fun clear() {
        prefs.edit().clear().apply()
    }

    private fun derive(pin: CharArray, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(pin, salt, ITERATIONS, KEY_LENGTH_BITS)
        return try {
            SecretKeyFactory.getInstance(KEY_ALGORITHM).generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    private fun String.fromHex(): ByteArray =
        chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private companion object {
        const val PREFS_NAME = "locky_pin"
        const val KEY_SALT = "pin_salt"
        const val KEY_HASH = "pin_hash"

        const val KEY_ALGORITHM = "PBKDF2WithHmacSHA256"
        const val ITERATIONS = 120_000
        const val KEY_LENGTH_BITS = 256
        const val SALT_BYTES = 16
    }
}
