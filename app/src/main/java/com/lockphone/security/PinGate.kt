package com.lockphone.security

class PinGate(
    private val clock: () -> Long,
    private val maxFailures: Int = 5,
    private val cooldownMs: Long = 60_000L,
) {
    private var failures = 0
    private var lockedUntil = 0L

    fun canAttempt(): Boolean = clock() >= lockedUntil

    fun remainingLockMs(): Long = (lockedUntil - clock()).coerceAtLeast(0)

    fun recordFailure() {
        failures++
        if (failures >= maxFailures) {
            lockedUntil = clock() + cooldownMs
            failures = 0
        }
    }

    fun recordSuccess() {
        failures = 0
        lockedUntil = 0
    }
}
