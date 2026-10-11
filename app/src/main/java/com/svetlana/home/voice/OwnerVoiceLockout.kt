package com.svetlana.home.voice

/**
 * Защита от перебора голосом: после [maxFails] отказов подряд голосовое управление
 * ставится на паузу на [lockMs]. Успешная проверка владельца сбрасывает счётчик.
 * Состояние можно снять ([snapshot]) и восстановить ([restore]): перезапуск приложения
 * не обнуляет попытки. Чистая логика (часы подменяемые) для юнит-тестов.
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

    /** Состояние для сохранения: число неудач и момент окончания паузы. */
    @Synchronized
    fun snapshot(): Pair<Int, Long> = fails to lockedUntil

    /**
     * Восстановить сохранённое состояние. Берём худшее из текущего и сохранённого;
     * пауза не больше [lockMs] от текущего момента (часы могли уйти назад).
     */
    @Synchronized
    fun restore(savedFails: Int, savedUntil: Long) {
        fails = maxOf(fails, savedFails.coerceIn(0, maxFails - 1))
        val cap = clock() + lockMs
        lockedUntil = maxOf(lockedUntil, minOf(savedUntil, cap))
    }
}
