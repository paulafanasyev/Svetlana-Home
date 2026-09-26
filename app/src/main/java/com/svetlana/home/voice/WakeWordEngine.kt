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
        job = scope.launch {
            while (true) {
                try {
                    val enabled = settings.wakeWordEnabled.first()
                    if (!enabled) { delay(1000); continue }
                    _state.value = State.LISTENING
                    recognizer.startListening()
                    // окно прослушивания
                    delay(4000)
                    recognizer.stopListening()
                    delay(400)
                    // КЛЮЧВОЕ место (аудит п.8): ранее результат STT никогда
                    // не читался — слово пробуждения физически не могло быть
                    // обнаружено. Теперь берём распознанный текст и проверяем.
                    processSttResult()
                } catch (t: Throwable) {
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
    private fun processSttResult() {
        val current = recognizer.result.value ?: return
        if (current !is SvetlanaSpeechRecognizer.SttResult.Success) return
        val command = matchWakeWord(current.text) ?: return
        Log.i(TAG, "Слово пробуждения обнаружено, команда: $command")
        _detection.value = command
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
