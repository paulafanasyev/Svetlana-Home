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
 * Безопасность: пока Светлана говорит (и короткое время после), микрофон не слушается; окно,
 * в которое попала её речь, отбрасывается — она не может «приказать» самой себе.
 * Распознанная команда считается НЕАУТЕНТИФИЦИРОВАННОЙ (см. [VoicePolicy]).
 */
class WakeWordEngine(
    private val context: Context,
    private val recognizer: SvetlanaSpeechRecognizer,
    private val settings: SettingsRepository
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _state = MutableStateFlow(State.IDLE)
    val state: StateFlow<State> = _state.asStateFlow()

    private val _detection = MutableStateFlow<String?>(null)
    val detection: StateFlow<String?> = _detection.asStateFlow()

    private var job: kotlinx.coroutines.Job? = null

    enum class State { IDLE, LISTENING, DETECTED }

    fun start() {
        if (job?.isActive == true) return
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
                    if (recognizer.session.isUserSessionActive || recognizer.foregroundSession.value) {
                        _state.value = State.IDLE
                        delay(500)
                        continue
                    }
                    if (!recognizer.session.canStartNewSession) {
                        _state.value = State.IDLE
                        delay(500)
                        continue
                    }
                    // Не слушаем, пока Светлана говорит или только что закончила (эхо).
                    if (!SpeechOutputGate.isQuiet()) {
                        _state.value = State.IDLE
                        delay(200)
                        continue
                    }
                    val windowStart = SpeechOutputGate.now()
                    _state.value = State.LISTENING
                    recognizer.startListening()
                    val result = recognizer.awaitResult(4_000L)
                    val finalResult = if (result == null) {
                        recognizer.stopListening()
                        recognizer.awaitResult(1_200L)
                    } else result
                    if (SpeechOutputGate.spokeSince(windowStart)) {
                        // В окно мог попасть голос самой Светланы — результат не используем.
                        Log.i(TAG, "Окно пересеклось с речью Светланы — отброшено")
                    } else {
                        processSttResult(finalResult)
                    }
                    recognizer.finishSession()
                    waitForDetectionConsumption()
                } catch (t: Throwable) {
                    recognizer.finishSession()
                    Log.w(TAG, "Цикл wake word прерван", t)
                    delay(2000)
                }
            }
        }
    }

    private fun processSttResult(current: SvetlanaSpeechRecognizer.SttResult? = recognizer.result.value) {
        current ?: return
        if (current !is SvetlanaSpeechRecognizer.SttResult.Success) return
        val command = matchWakeWord(current.text) ?: return
        Log.i(TAG, "Слово пробуждения обнаружено")
        _detection.value = command
    }

    private suspend fun waitForDetectionConsumption() {
        while (_detection.value != null) delay(100)
    }

    fun consumeDetection() { _detection.value = null }

    fun stop() {
        job?.cancel()
        job = null
        _state.value = State.IDLE
        recognizer.stopListening()
        recognizer.finishSession()
    }

    val listeningKind: String get() = "STT_POLLING (не always-on low-power детектор)"

    fun matchWakeWord(phrase: String): String? {
        val command = WakeWordMatcher.matchWakeWord(phrase)
        if (command != null || WakeWordMatcher.containsWakeWord(phrase)) {
            _state.value = State.DETECTED
        }
        return command
    }

    companion object { private const val TAG = "WakeWord" }
}
