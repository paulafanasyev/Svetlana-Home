package com.svetlana.home.bridge

/**
 * Brute-force protection for the pairing code, per client IP:
 * [perClientLimit] wrong codes inside [windowMs] lock that client for [lockMs].
 *
 * No global lock on purpose: it would let anyone on the LAN block the owner's
 * first pairing. With 32^8 possible codes and 5 tries per IP per 10 minutes,
 * even a whole /24 of rotated addresses needs millions of years.
 */
class BridgeRateLimiter(
    private val clock: () -> Long = System::currentTimeMillis,
    private val perClientLimit: Int = 5,
    private val windowMs: Long = 10 * 60_000L,
    private val lockMs: Long = 10 * 60_000L,
) {
    private val failures = HashMap<String, MutableList<Long>>()
    private val lockedUntil = HashMap<String, Long>()

    @Synchronized
    fun isLocked(client: String): Boolean = clock() < (lockedUntil[client] ?: 0L)

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
        if (failures.size > MAX_TRACKED) failures.entries.removeAll { it.value.isEmpty() }
        if (lockedUntil.size > MAX_TRACKED) lockedUntil.entries.removeAll { it.value <= now }
    }

    @Synchronized
    fun recordSuccess(client: String) {
        failures.remove(client)
    }

    private companion object {
        const val MAX_TRACKED = 1024
    }
}
