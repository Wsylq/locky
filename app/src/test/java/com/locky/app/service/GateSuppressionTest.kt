package com.locky.app.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rule that stops the lock re-gating the app it just unlocked.
 *
 * The behaviour these cover is the one that made the lock impossible to open with
 * a zero grace period: dismissing the lock returns focus to the locked app, and
 * treating that as a fresh launch gated it again immediately, forever.
 */
class GateSuppressionTest {

    private val suppression = GateSuppression()

    @Test
    fun `an app with no unlock is gated`() {
        assertTrue(suppression.shouldGate("com.example.chat"))
    }

    @Test
    fun `an app is not gated again immediately after unlocking`() {
        suppression.onSatisfied("com.example.chat")

        // The event that arrives when the lock dismisses itself.
        assertFalse(suppression.shouldGate("com.example.chat"))
        assertFalse(suppression.shouldGate("com.example.chat"))
    }

    @Test
    fun `returning after leaving is gated, even with no grace period`() {
        suppression.onSatisfied("com.example.chat")
        assertFalse(suppression.shouldGate("com.example.chat"))

        // Another app comes forward, which means chat was genuinely left.
        assertTrue(suppression.shouldGate("com.example.launcher"))

        // Coming back must ask again.
        assertTrue(suppression.shouldGate("com.example.chat"))
    }

    @Test
    fun `leaving through an unprotected app is what ends the suppression`() {
        suppression.onSatisfied("com.example.chat")

        assertTrue(suppression.shouldGate("com.android.launcher"))
        assertTrue(suppression.shouldGate("com.example.chat"))
    }

    @Test
    fun `unlocking a second app suppresses that one too`() {
        suppression.onSatisfied("com.example.chat")
        assertTrue(suppression.shouldGate("com.example.bank"))

        suppression.onSatisfied("com.example.bank")
        assertFalse(suppression.shouldGate("com.example.bank"))

        // And chat was aged out when bank came forward, so it asks again.
        assertTrue(suppression.shouldGate("com.example.chat"))
    }

    @Test
    fun `clear makes the next app gated`() {
        suppression.onSatisfied("com.example.chat")

        // "Lock now": the user has asked for the next protected app to be gated.
        suppression.clear()

        assertTrue(suppression.shouldGate("com.example.chat"))
    }
}
