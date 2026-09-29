package com.svetlana.home.voice

import android.content.Context
import android.util.Log
import com.svetlana.home.store.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first

/**
 * WakeWordEngine — архитектура распознавания слова пробуждения (ТЗ §21).
 *
 * Wake words: Света, Светочка, Светлана.
 *
 * Реализация: на устройстве используется системный распознаватель речи в
 * режиме короткого прослушивания. Распознанный текст проверяется на наличие
 * слова пробуждения полностью локально — никакой голос не покидает устройство.
 *
 * Wake word listening активируется только когда пользователь включил его
 * в настройках и предоставил разрешение на микрофон.
 */
class WakeWordEngine(
    private val context: Context,
    private val recognizer: SvetlanaSpeechRecognizer,
    private val settings: SettingsRepository
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _state = MutableStateFlow(State.IDLE)
    val state: StateFlow<State> = _state.asStateFlow()

    /**
     * Распознанная фраза, если она содержит слово пробуждения.
     * HomeViewModel слушает этот поток и запускает команду.
     */
    private val _detection = MutableStateFlow<String?>(null)
    val detection: StateFlow<String?> = _detection.asStateFlow()

    private var job: kotlinx.coroutines.Job? = null

    enum class State { IDLE, LISTENING, DETECTED }

    fun start() {
        if (job?.isActive == true) return
        // Background wake-word mode must never silently fall back to a
        // potentially network-backed SpeechRecognizer.
        if (!recognizer.isOnDeviceAvailable) {
            _state.value = State.IDLE
            Log.i(TAG, "Wake word disabled: on-device speech recognition is unavailable")
            return
        }
        job = scope.launch {
            while (true) {
                try {
                    val enabled = settings.wakeWordEnabled.first()
                    if (!enabled) { delay(1000); continue }
                    // Аудит-2026 проблема 1: если пользователь сейчас говорит
                    // (активна foreground-сессия) — НЕ перехватываем микрофон.
                    // Раньше фоновый цикл убивал активную сессию пользователя.
                    // Аудит §18: проверяем явное состояние сессии, а не только флаг.
                    if (recognizer.session.isUserSessionActive || recognizer.foregroundSession.value) {
                        _state.value = State.IDLE
                        delay(500)
                        continue
                    }
                    // Аудит §18: дополнительная защита — startListening() сам
                    // отклонит запуск, если сессия всё же активна.
                    if (!recognizer.session.canStartNewSession) {
                        _state.value = State.IDLE
                        delay(500)
                        continue
                    }
                    _state.value = State.LISTENING
                    recognizer.startListening()
                    // Ожидаем финальный результат асинхронно. Нельзя полагаться
                    // на фиксированные 400 мс после stopListening(): onResults()
                    // может прийти позже и wake word будет потерян.
                    val result = recognizer.awaitResult(4_000L)
                    if (result == null) {
                        recognizer.stopListening()
                        // Даём SpeechRecognizer короткое дополнительное окно
                        // на доставку финального onResults после stopListening().
                        val finalResult = recognizer.awaitResult(1_200L)
                        processSttResult(finalResult)
                    } else {
                        processSttResult(result)
                    }
                    // Эта сессия больше не держит микрофон. Следующий цикл
                    // разрешён только после завершения текущего результата.
                    recognizer.finishSession()
                    waitForDetectionConsumption()
                } catch (t: Throwable) {
                    // Любая ошибка после старта STT не должна оставлять
                    // VoiceSessionStateMachine в STOPPED/PROCESSING.
                    recognizer.finishSession()
                    Log.w(TAG, "Цикл wake word прерван", t)
                    delay(2000)
                }
            }
        }
    }

    /**
     * Читает результат STT за только что завершённое окно прослушивания и
     * проверяет его на слово пробуждения. Полный путь:
     *   Микрофон → STT → WakeWordMatcher → DETECTED → команда
     */
    private fun processSttResult(current: SvetlanaSpeechRecognizer.SttResult? = recognizer.result.value) {
        current ?: return
        if (current !is SvetlanaSpeechRecognizer.SttResult.Success) return
        val command = matchWakeWord(current.text) ?: return
        Log.i(TAG, "Слово пробуждения обнаружено, команда: $command")
        _detection.value = command
    }

    private suspend fun waitForDetectionConsumption() {
        while (_detection.value != null) delay(100)
    }

    /**
     * Сброс detections после того, как HomeViewModel обработал команду.
     */
    fun consumeDetection() { _detection.value = null }

    fun stop() {
        job?.cancel()
        job = null
        _state.value = State.IDLE
        recognizer.stopListening()
        recognizer.finishSession()
    }

    /**
     * Тип прослушивания (для прозрачности перед пользователем и аудита п.12).
     *
     * Текущая реализация — STT_POLLING: системный распознаватель опрашивается
     * короткими окнами. Это НЕ always-on low-power аппаратный детектор:
     * расходует больше батареи и не работает в Doze.
     *
     * Честно сообщаем это, вместо того чтобы заявлять «настоящий wake word».
     */
    val listeningKind: String get() = "STT_POLLING (не always-on low-power детектор)"

    /**
     * Проверить, содержит ли фраза слово пробуждения.
     * Возвращает команду без wake word или null.
     * Делегирует в WakeWordMatcher — единый источник логики (покрыт тестами).
     */
    fun matchWakeWord(phrase: String): String? {
        val command = WakeWordMatcher.matchWakeWord(phrase)
        if (command != null || WakeWordMatcher.containsWakeWord(phrase)) {
            _state.value = State.DETECTED
        }
        return command
    }

    companion object { private const val TAG = "WakeWord" }
}
