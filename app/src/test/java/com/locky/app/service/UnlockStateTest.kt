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
}
