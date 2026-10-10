package com.svetlana.home.bridge

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class BridgeRateLimiterTest {
    private var now = 0L
    private val limiter = BridgeRateLimiter(clock = { now }, perClientLimit = 3, globalLimit = 5, windowMs = 1_000, lockMs = 10_000)

    @Test
    fun locksClientAfterLimitAndUnlocksLater() {
        repeat(3) { limiter.recordFailure("a") }
        assertThat(limiter.isLocked("a")).isTrue()
        assertThat(limiter.isLocked("b")).isFalse()
        now = 10_001
        assertThat(limiter.isLocked("a")).isFalse()
    }

    @Test
    fun oldFailuresExpire() {
        limiter.recordFailure("a")
        limiter.recordFailure("a")
        now = 5_000
        limiter.recordFailure("a")
        assertThat(limiter.isLocked("a")).isFalse()
    }

    @Test
    fun globalLockStopsIpRotation() {
        listOf("a", "b", "c", "d", "e").forEach { limiter.recordFailure(it) }
        assertThat(limiter.isLocked("fresh")).isTrue()
    }

    @Test
    fun successResetsClientCounter() {
        limiter.recordFailure("a")
        limiter.recordFailure("a")
        limiter.recordSuccess("a")
        limiter.recordFailure("a")
        assertThat(limiter.isLocked("a")).isFalse()
    }
}
