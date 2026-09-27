package com.locky.app.service

/**
 * Stops the lock from immediately re-gating the app it just unlocked.
 *
 * Hiding the lock is itself a foreground change: dismissing the overlay (or the
 * biometric activity finishing) hands focus back to the app that was locked, and
 * the system reports that as a new window state. Without this, an app unlocked a
 * moment ago is seen as locked again on the very next event and gated a second
 * time — which for a zero grace period means the prompt appears over and over
 * with no way to get past it, and for a very short one means the same thing on a
 * slow device where the event arrives late.
 *
 * The rule is deliberately narrow: the app that was just unlocked is not gated
 * again until a *different* app has been in the foreground. Leaving and coming
 * back re-locks it, even with no grace period at all, which is what a user asking
 * for no grace period actually wants — a prompt each time they open something,
 * not a prompt each time the system moves focus.
 */
class GateSuppression {

    @Volatile
    private var satisfiedPackage: String? = null

    /**
     * True when [packageName] should be gated.
     *
     * Calling this also ages out the suppression: any package other than the one
     * just unlocked means that app was genuinely left, so the next time it comes
     * to the foreground it is gated normally.
     */
    fun shouldGate(packageName: String): Boolean {
        if (satisfiedPackage == packageName) return false
        satisfiedPackage = null
        return true
    }

    /** Records that [packageName] has just been unlocked. */
    fun onSatisfied(packageName: String) {
        satisfiedPackage = packageName
    }

    /** Forgets any suppression, so the next foreground app is gated. */
    fun clear() {
        satisfiedPackage = null
    }
}
