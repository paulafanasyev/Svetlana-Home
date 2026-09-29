package com.svetlana.home.voice

/**
 * Аудит §18: явная машина состояний голосовой сессии.
 *
 * Раньше wake-word цикл мог стартовать новую сессию, пока предыдущая
 * не закончила onResults()/onError() — получались гонки и потерянные
 * результаты. Теперь переходы строго определены:
 *
 *   IDLE → STARTING → LISTENING → RESULT_RECEIVED → PROCESSING → STOPPED
 *                                        │
 *                                        └→ ERROR → STOPPED
 *
 * WakeWordEngine видит состояние и не стартует слушание, пока сессия
 * пользователя не вернулась в IDLE.
 */
enum class VoiceSessionState {
    /** Нет активной сессии. Можно начинать новую. */
    IDLE,

    /** Создаём распознаватель и отправляем startListening(). */
    STARTING,

    /** Система слушает микрофон. */
    LISTENING,

    /** Получен финальный результат, обрабатываем его. */
    RESULT_RECEIVED,

    /** Обработка команды (роутинг, выполнение действия). */
    PROCESSING,

    /** Сессия завершена (включая ошибку). Ресурсы освобождены. */
    STOPPED
}

/**
 * Допустимые переходы. Всё остальное запрещено — это защищает от гонок.
 */
private val ALLOWED_TRANSITIONS = mapOf(
    VoiceSessionState.IDLE to setOf(VoiceSessionState.STARTING),
    VoiceSessionState.STARTING to setOf(VoiceSessionState.LISTENING, VoiceSessionState.STOPPED),
    VoiceSessionState.LISTENING to setOf(
        VoiceSessionState.RESULT_RECEIVED,
        VoiceSessionState.STOPPED
    ),
    VoiceSessionState.RESULT_RECEIVED to setOf(VoiceSessionState.PROCESSING, VoiceSessionState.STOPPED),
    VoiceSessionState.PROCESSING to setOf(VoiceSessionState.IDLE, VoiceSessionState.STOPPED),
    VoiceSessionState.STOPPED to setOf(VoiceSessionState.IDLE, VoiceSessionState.STARTING)
)

/**
 * Thread-safe держатель состояния сессии.
 */
class VoiceSessionStateMachine {
    @Volatile
    private var _state: VoiceSessionState = VoiceSessionState.IDLE

    val state: VoiceSessionState get() = _state

    /**
     * Безопасно можно ли стартовать новую сессию.
     * WakeWordEngine опрашивает это перед startListening().
     */
    val canStartNewSession: Boolean
        get() = _state == VoiceSessionState.IDLE || _state == VoiceSessionState.STOPPED

    /**
     * Активна ли приоритетная сессия пользователя — её нельзя прерывать.
     */
    val isUserSessionActive: Boolean
        get() = _state == VoiceSessionState.STARTING ||
            _state == VoiceSessionState.LISTENING ||
            _state == VoiceSessionState.RESULT_RECEIVED ||
            _state == VoiceSessionState.PROCESSING

    /**
     * Переход с проверкой. Незаконный переход логируется и отклоняется.
     * @return true если переход выполнен.
     */
    @Synchronized
    fun transitionTo(target: VoiceSessionState): Boolean {
        val allowed = ALLOWED_TRANSITIONS[_state] ?: emptySet()
        if (target !in allowed) {
            // STOPPED → IDLE/STARTING и IDLE → STARTING закрывают новый цикл.
            if (!(_state == VoiceSessionState.STOPPED && target == VoiceSessionState.IDLE)) {
                return false
            }
        }
        _state = target
        return true
    }

    /**
     * Завершить текущую STT-сессию после того, как потребитель получил результат
     * (или после таймаута/ошибки). Это единственный переход, который закрывает
     * цикл и гарантирует возможность следующего wake-word / пользовательского ввода.
     */
    @Synchronized
    fun finish() {
        _state = VoiceSessionState.IDLE
    }

    /** Сброс в IDLE — для полного освобождения ресурсов. */
    @Synchronized
    fun reset() {
        _state = VoiceSessionState.IDLE
    }
}
