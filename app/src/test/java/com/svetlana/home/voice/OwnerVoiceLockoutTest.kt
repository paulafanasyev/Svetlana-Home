package com.svetlana.home.voice

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class OwnerVoiceLockoutTest {

    @Test fun restoredLockSurvivesRestart() {
        var now = 10_000L
        val before = OwnerVoiceLockout(maxFails = 5, lockMs = 60_000L, clock = { now })
        repeat(5) { before.onFailure() }
        val saved = before.snapshot()
        val after = OwnerVoiceLockout(maxFails = 5, lockMs = 60_000L, clock = { now })
        after.restore(saved.first, saved.second)
        assertThat(after.isLocked()).isTrue()
        now += 60_000L
        assertThat(after.isLocked()).isFalse()
    }

    @Test fun restoredFailuresKeepCounting() {
        val now = 0L
        val before = OwnerVoiceLockout(maxFails = 5, lockMs = 60_000L, clock = { now })
        repeat(4) { before.onFailure() }
        val after = OwnerVoiceLockout(maxFails = 5, lockMs = 60_000L, clock = { now })
        after.restore(before.snapshot().first, before.snapshot().second)
        after.onFailure()
        assertThat(after.isLocked()).isTrue()
    }

    @Test fun restoreCapsBogusFutureLock() {
        var now = 0L
        val l = OwnerVoiceLockout(maxFails = 5, lockMs = 60_000L, clock = { now })
        l.restore(0, Long.MAX_VALUE)
        assertThat(l.remainingMs()).isAtMost(60_000L)
        now += 60_000L
        assertThat(l.isLocked()).isFalse()
    }

    @Test fun restoreIgnoresNegativeAndHugeFailCounts() {
        val now = 0L
        val l = OwnerVoiceLockout(maxFails = 5, lockMs = 60_000L, clock = { now })
        l.restore(-7, 0L)
        assertThat(l.snapshot().first).isEqualTo(0)
        l.restore(1_000, 0L)
        assertThat(l.snapshot().first).isEqualTo(4)
        assertThat(l.isLocked()).isFalse()
    }
}
