package com.locky.app.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The grace period that stops a user re-entering the PIN on every app switch. */
class UnlockStateTest {

    private var now = 0L
    private val clock = { now }

    @Test
    fun `an app with no grant is locked`() {
        assertFalse(UnlockState(graceMillis = 1_000, clock = clock).isUnlocked("com.example.chat"))
    }

    @Test
    fun `a granted app is unlocked inside the grace period`() {
        val state = UnlockState(graceMillis = 1_000, clock = clock)
        state.grant("com.example.chat")

        assertTrue(state.isUnlocked("com.example.chat"))
    }

    @Test
    fun `the grace period is absolute, not sliding`() {
        val state = UnlockState(graceMillis = 1_000, clock = clock)
        state.grant("com.example.chat")

        // Reading the state must not extend it.
        now += 900
        assertTrue(state.isUnlocked("com.example.chat"))
        now += 200

        assertFalse(state.isUnlocked("com.example.chat"))
    }

    @Test
    fun `grants do not leak between packages`() {
        val state = UnlockState(graceMillis = 1_000, clock = clock)
        state.grant("com.example.chat")

        assertFalse(state.isUnlocked("com.example.bank"))
    }

    @Test
    fun `revoke ends a grace period early`() {
        val state = UnlockState(graceMillis = 60_000, clock = clock)
        state.grant("com.example.chat")
        state.revoke("com.example.chat")

        assertFalse(state.isUnlocked("com.example.chat"))
    }

    @Test
    fun `revokeAll ends every grace period`() {
        val state = UnlockState(graceMillis = 60_000, clock = clock)
        state.grant("com.example.chat")
        state.grant("com.example.bank")
        state.revokeAll()

        assertFalse(state.isUnlocked("com.example.chat"))
        assertFalse(state.isUnlocked("com.example.bank"))
    }

    @Test
    fun `prune drops expired entries`() {
        val state = UnlockState(graceMillis = 1_000, clock = clock)
        state.grant("com.example.old")

        now += 2_000
        state.grant("com.example.fresh")
        state.prune()

        // The fresh grant survives pruning; the expired one is gone, and asking
        // about it still reports locked rather than granting by accident.
        assertTrue(state.isUnlocked("com.example.fresh"))
        assertFalse(state.isUnlocked("com.example.old"))
    }

    @Test
    fun `a zero grace period locks on the next check`() {
        val state = UnlockState(graceMillis = 0L, clock = clock)
        state.grant("com.example.chat")

        // "Immediately" is a real option, not a special case: the deadline has
        // already passed by the time anything asks, so nothing stays unlocked.
        assertFalse(state.isUnlocked("com.example.chat"))
    }

    @Test
    fun `the grace period is re-read on every grant`() {
        // The re-lock setting is user-changeable while the watcher is already
        // running, so the period cannot be captured once at construction. Changing
        // it has to affect the very next unlock, with no service restart.
        var grace = 60_000L
        val state = UnlockState(graceMillis = { grace }, clock = clock)

        state.grant("com.example.chat")
        assertTrue(state.isUnlocked("com.example.chat"))

        grace = 0L
        state.grant("com.example.bank")
        assertFalse(state.isUnlocked("com.example.bank"))

        // The earlier grant keeps the period it was actually given rather than
        // being retroactively rewritten by the new setting.
        assertTrue(state.isUnlocked("com.example.chat"))
    }

    @Test
    fun `the app just unlocked is not gated again while it is still in front`() {
        // Dismissing the lock re-exposes the app underneath as the foreground
        // window, which looks identical to the user opening it again. With no
        // grace period at all, re-gating on that event is an inescapable loop:
        // unlock, the app reclaims the foreground, ask again, forever.
        val state = UnlockState(graceMillis = 0L, clock = clock)
        state.grant("com.example.chat")

        assertTrue(state.isAuthenticatedAndPresent("com.example.chat"))
        // And the grace period is genuinely over, so only the latch is standing in.
        assertFalse(state.isUnlocked("com.example.chat"))
    }

    @Test
    fun `leaving and returning is gated again`() {
        val state = UnlockState(graceMillis = 0L, clock = clock)
        state.grant("com.example.chat")

        // The user goes somewhere else, which is what ends the latch.
        assertFalse(state.isAuthenticatedAndPresent("com.example.launcher"))

        // Coming back must ask again, or "no grace period" would mean "once ever".
        assertFalse(state.isAuthenticatedAndPresent("com.example.chat"))
        assertFalse(state.isUnlocked("com.example.chat"))
    }

    @Test
    fun `nothing is treated as authenticated before the first unlock`() {
        val state = UnlockState(graceMillis = 0L, clock = clock)

        assertFalse(state.isAuthenticatedAndPresent("com.example.chat"))
    }
}
