package com.svetlana.home.voice

/**
 * Защита от перебора голосом: после [maxFails] отказов подряд голосовое управление
 * ставится на паузу на [lockMs]. Успешная проверка владельца сбрасывает счётчик.
 * Чистая логика (часы подменяемые) для юнит-тестов.
 */
class OwnerVoiceLockout(
    private val maxFails: Int = 5,
    private val lockMs: Long = 5 * 60_000L,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    private var fails = 0
    private var lockedUntil = 0L

    @Synchronized
    fun remainingMs(): Long = (lockedUntil - clock()).coerceAtLeast(0L)

    @Synchronized
    fun isLocked(): Boolean = clock() < lockedUntil

    @Synchronized
    fun onSuccess() {
        fails = 0
    }

    @Synchronized
    fun onFailure() {
        fails++
        if (fails >= maxFails) {
            lockedUntil = clock() + lockMs
            fails = 0
        }
    }

    @Synchronized
    fun reset() {
        fails = 0
        lockedUntil = 0L
    }
}
