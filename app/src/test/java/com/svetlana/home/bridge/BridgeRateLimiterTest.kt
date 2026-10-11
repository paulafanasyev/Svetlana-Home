package com.svetlana.home.bridge

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class BridgeRateLimiterTest {
    private var now = 0L
    private val limiter = BridgeRateLimiter(clock = { now }, perClientLimit = 3, windowMs = 1_000, lockMs = 10_000)

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
    fun attackerCannotLockOutOtherClients() {
        listOf("a", "b", "c", "d", "e").forEach { ip -> repeat(3) { limiter.recordFailure(ip) } }
        assertThat(limiter.isLocked("a")).isTrue()
        assertThat(limiter.isLocked("owner-pc")).isFalse()
    }

    @Test
    fun successResetsClientCounter() {
        limiter.recordFailure("a")
        limiter.recordFailure("a")
        limiter.recordSuccess("a")
        limiter.recordFailure("a")
        assertThat(limiter.isLocked("a")).isFalse()
    }

    @Test
    fun globalBudgetStopsAddressRotationButNotTheOwner() {
        val global = BridgeRateLimiter(clock = { now }, perClientLimit = 5, windowMs = 1_000, lockMs = 10_000, globalLimit = 10)
        global.recordSuccess("owner-pc")
        (1..10).forEach { global.recordFailure("10.0.0.$it") }
        // Addresses that already guessed wrong are stopped...
        assertThat(global.isLocked("10.0.0.1")).isTrue()
        // ...but a PC pairing for the first time and the paired PC are not (no LAN DoS of the owner).
        assertThat(global.isLocked("192.168.1.50")).isFalse()
        assertThat(global.isLocked("owner-pc")).isFalse()
        now = 1_001
        assertThat(global.isLocked("10.0.0.1")).isFalse()
    }

    @Test
    fun memoryIsBounded() {
        val small = BridgeRateLimiter(clock = { now }, perClientLimit = 1, maxTracked = 4, globalLimit = 1_000)
        (1..100).forEach { small.recordFailure("ip$it") }
        assertThat(small.isLocked("ip100")).isTrue()
        assertThat(small.isLocked("ip1")).isFalse()
    }
}
