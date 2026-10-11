package com.svetlana.home.voice

import java.util.concurrent.atomic.AtomicInteger

/**
 * SpeechOutputGate — «Светлана сейчас говорит». Нужен, чтобы фоновое слово пробуждения не слышало
 * собственный голос Светланы (ответ «Света, позвони…» не должен стать новой командой).
 *
 * Счётчик активных фраз + «хвост» после последней фразы (эхо комнаты). Если колбэк синтеза
 * потерялся, через [STUCK_MS] счётчик считается зависшим и сбрасывается — микрофон не
 * блокируется навсегда. Чистый Kotlin (без Android), покрыт unit-тестами.
 */
object SpeechOutputGate {
    const val TAIL_MS = 800L
    const val STUCK_MS = 60_000L

    private val active = AtomicInteger(0)
    @Volatile private var lastChangeAt = Long.MIN_VALUE / 2

    /** Источник времени (мс); подменяется в тестах. */
    @Volatile var clock: () -> Long = { System.nanoTime() / 1_000_000 }

    fun began() { active.incrementAndGet(); lastChangeAt = clock() }

    fun ended() {
        if (active.decrementAndGet() < 0) active.set(0)
        lastChangeAt = clock()
    }

    /** Всё остановлено (stop/flush). */
    fun reset() { active.set(0); lastChangeAt = clock() }

    private fun unstick(now: Long) {
        if (active.get() > 0 && now - lastChangeAt > STUCK_MS) active.set(0)
    }

    val isSpeaking: Boolean get() { unstick(clock()); return active.get() > 0 }

    /** Можно слушать: ничего не говорится и прошёл «хвост» после последней фразы. */
    fun isQuiet(tailMs: Long = TAIL_MS): Boolean {
        val now = clock(); unstick(now)
        return active.get() == 0 && now - lastChangeAt >= tailMs
    }

    /** Говорила ли Светлана хоть раз с момента [since] (тогда записанное окно может содержать её голос). */
    fun spokeSince(since: Long): Boolean {
        unstick(clock())
        return active.get() > 0 || lastChangeAt >= since
    }

    fun now(): Long = clock()
}
