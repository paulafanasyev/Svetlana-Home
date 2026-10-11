package com.svetlana.home.bridge

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class BridgeReplayGuardTest {
    private var now = 1_000_000L
    private val guard = BridgeReplayGuard(clock = { now }, maxSkewMs = 1_000, capacity = 3)

    @Test
    fun nonceIsSingleUse() {
        assertThat(guard.check("a", now)).isEqualTo(BridgeReplayGuard.Verdict.OK)
        assertThat(guard.check("a", now)).isEqualTo(BridgeReplayGuard.Verdict.REPLAY)
    }

    @Test
    fun timestampsOutsideWindowAreStale() {
        assertThat(guard.check("a", now - 1_001)).isEqualTo(BridgeReplayGuard.Verdict.STALE)
        assertThat(guard.check("b", now + 1_001)).isEqualTo(BridgeReplayGuard.Verdict.STALE)
    }

    @Test
    fun fullCacheFailsClosedThenRecoversAfterExpiry() {
        listOf("a", "b", "c").forEach { assertThat(guard.check(it, now)).isEqualTo(BridgeReplayGuard.Verdict.OK) }
        assertThat(guard.check("d", now)).isEqualTo(BridgeReplayGuard.Verdict.BUSY)
        now += 1_001
        assertThat(guard.check("d", now)).isEqualTo(BridgeReplayGuard.Verdict.OK)
        assertThat(guard.size()).isEqualTo(1)
    }
}
