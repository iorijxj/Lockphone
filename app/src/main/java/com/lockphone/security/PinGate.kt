package com.lockphone.security

class PinGate(
    private val clock: () -> Long,
    private val maxFailures: Int = 5,
    private val cooldownMs: Long = 60_000L,
    initialLockedUntil: Long = 0L,
    private val onLockedUntilChanged: (Long) -> Unit = {},
) {
    private var failures = 0
    private var lockedUntil = initialLockedUntil

    fun canAttempt(): Boolean = clock() >= lockedUntil

    fun remainingLockMs(): Long = (lockedUntil - clock()).coerceAtLeast(0)

    fun restore(lockedUntil: Long) {
        this.lockedUntil = lockedUntil
    }

    fun recordFailure() {
        failures++
        if (failures >= maxFailures) {
            lockedUntil = clock() + cooldownMs
            failures = 0
            onLockedUntilChanged(lockedUntil)
        }
    }

    fun recordSuccess() {
        failures = 0
        lockedUntil = 0
        onLockedUntilChanged(0)
    }
}
