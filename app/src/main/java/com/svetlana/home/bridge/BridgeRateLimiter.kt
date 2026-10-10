package com.svetlana.home.bridge

/**
 * Brute-force protection for the pairing code.
 * - per client: [perClientLimit] wrong codes inside [windowMs] lock that client for [lockMs];
 * - globally: [globalLimit] wrong codes inside [windowMs] lock every client that has not
 *   paired successfully yet (defeats IP rotation without letting an attacker lock out the owner's PC).
 */
class BridgeRateLimiter(
    private val clock: () -> Long = System::currentTimeMillis,
    private val perClientLimit: Int = 5,
    private val globalLimit: Int = 30,
    private val windowMs: Long = 10 * 60_000L,
    private val lockMs: Long = 10 * 60_000L,
) {
    private val failures = HashMap<String, MutableList<Long>>()
    private val globalFailures = ArrayList<Long>()
    private val lockedUntil = HashMap<String, Long>()
    private var globalLockedUntil = 0L
    private val trusted = HashSet<String>()

    @Synchronized
    fun isLocked(client: String): Boolean {
        val now = clock()
        if (now < (lockedUntil[client] ?: 0L)) return true
        return now < globalLockedUntil && client !in trusted
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
        globalFailures.add(now)
        globalFailures.removeAll { now - it > windowMs }
        if (globalFailures.size >= globalLimit) {
            globalLockedUntil = now + lockMs
            globalFailures.clear()
        }
        if (failures.size > MAX_TRACKED) failures.clear()
        if (lockedUntil.size > MAX_TRACKED) lockedUntil.entries.removeAll { it.value <= now }
    }

    @Synchronized
    fun recordSuccess(client: String) {
        failures.remove(client)
        if (trusted.size < MAX_TRACKED) trusted.add(client)
    }

    private companion object {
        const val MAX_TRACKED = 1024
    }
}
