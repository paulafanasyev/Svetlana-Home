package com.svetlana.home.bridge

/**
 * Brute-force protection for the pairing code.
 *
 * - Per client IP: [perClientLimit] failed decryptions inside [windowMs] lock that
 *   client for [lockMs].
 * - Global budget: more than [globalLimit] failures from all clients inside
 *   [windowMs] lock every client that has not authenticated successfully in the
 *   last [trustMs]. This stops address-rotating attackers while the already
 *   paired PC keeps working (no lock-out of the owner).
 * - Memory is bounded (LRU, [maxTracked] entries per table).
 *
 * The code itself has 16 symbols from a 32-letter alphabet (80 bits), so even an
 * unthrottled attacker could not guess it; the limiter is defence in depth.
 */
class BridgeRateLimiter(
    private val clock: () -> Long = System::currentTimeMillis,
    private val perClientLimit: Int = 5,
    private val windowMs: Long = 10 * 60_000L,
    private val lockMs: Long = 10 * 60_000L,
    private val globalLimit: Int = 100,
    private val trustMs: Long = 24 * 60 * 60_000L,
    private val maxTracked: Int = 1024,
) {
    private val failures = lru<MutableList<Long>>()
    private val lockedUntil = lru<Long>()
    private val trustedUntil = lru<Long>()
    private val globalFailures = ArrayDeque<Long>()

    @Synchronized
    fun isLocked(client: String): Boolean {
        val now = clock()
        if (now < (lockedUntil[client] ?: 0L)) return true
        trimGlobal(now)
        val trusted = now < (trustedUntil[client] ?: 0L)
        return globalFailures.size >= globalLimit && !trusted
    }

    @Synchronized
    fun recordFailure(client: String) {
        val now = clock()
        globalFailures.addLast(now)
        trimGlobal(now)
        while (globalFailures.size > globalLimit * 2) globalFailures.removeFirst()
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
        trustedUntil[client] = clock() + trustMs
    }

    private fun trimGlobal(now: Long) {
        while (globalFailures.isNotEmpty() && now - globalFailures.first() > windowMs) globalFailures.removeFirst()
    }

    private fun <V> lru(): LinkedHashMap<String, V> = object : LinkedHashMap<String, V>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, V>?): Boolean = size > maxTracked
    }
}
