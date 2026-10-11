package com.svetlana.home.bridge

/**
 * Brute-force protection for the pairing code, per client IP:
 * [perClientLimit] wrong codes inside [windowMs] lock that client for [lockMs].
 *
 * No global lock on purpose: it would let anyone on the LAN block the owner's
 * first pairing. Memory is bounded: at most [maxTracked] clients are remembered
 * (least recently seen are evicted), so a flood of spoofed addresses can't exhaust RAM.
 * With 32^12 possible codes (protocol v2) online guessing is hopeless even across many IPs.
 */
class BridgeRateLimiter(
    private val clock: () -> Long = System::currentTimeMillis,
    private val perClientLimit: Int = 5,
    private val windowMs: Long = 10 * 60_000L,
    private val lockMs: Long = 10 * 60_000L,
    private val maxTracked: Int = MAX_TRACKED,
) {
    private val failures = lru<MutableList<Long>>()
    private val lockedUntil = lru<Long>()

    private fun <V> lru(): LinkedHashMap<String, V> = object : LinkedHashMap<String, V>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, V>?): Boolean = size > maxTracked
    }

    @Synchronized
    fun isLocked(client: String): Boolean {
        val until = lockedUntil[client] ?: return false
        if (clock() < until) return true
        lockedUntil.remove(client)
        return false
    }

    @Synchronized
    fun recordFailure(client: String) {
        val now = clock()
        val list = failures.getOrPut(client) { ArrayList() }
        list.add(now)
        list.removeAll { now - it > windowMs }
        if (list.size >= perClientLimit) {
            lockedUntil[client] = now + lockMs
            list.clear()
        }
    }

    @Synchronized
    fun recordSuccess(client: String) {
        failures.remove(client)
    }

    @Synchronized
    fun trackedClients(): Int = failures.size

    private companion object {
        const val MAX_TRACKED = 1024
    }
}
