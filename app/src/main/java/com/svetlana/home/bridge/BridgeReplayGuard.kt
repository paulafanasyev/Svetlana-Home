package com.svetlana.home.bridge

/**
 * One-time nonces for bridge protocol v2. A request is accepted only if its
 * (authenticated) timestamp is within [maxSkewMs] of the phone clock and its nonce
 * has not been seen inside the acceptance window. Memory is bounded: when the
 * cache is full of still-valid nonces, new requests are refused (fail closed)
 * instead of evicting entries an attacker could then replay.
 */
class BridgeReplayGuard(
    private val clock: () -> Long = System::currentTimeMillis,
    val maxSkewMs: Long = 120_000L,
    private val capacity: Int = 4096,
) {
    enum class Verdict { OK, STALE, REPLAY, BUSY }

    private val seen = LinkedHashMap<String, Long>()

    @Synchronized
    fun check(nonce: String, ts: Long): Verdict {
        val now = clock()
        if (ts < now - maxSkewMs || ts > now + maxSkewMs) return Verdict.STALE
        purge(now)
        if (seen.containsKey(nonce)) return Verdict.REPLAY
        if (seen.size >= capacity) return Verdict.BUSY
        // Keep until the timestamp can no longer pass the skew check.
        seen[nonce] = ts + maxSkewMs
        return Verdict.OK
    }

    @Synchronized
    fun size(): Int = seen.size

    private fun purge(now: Long) {
        val it = seen.entries.iterator()
        while (it.hasNext()) {
            if (it.next().value < now) it.remove()
        }
    }
}
