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

    private var job: kotlinx.coroutines.Job? = null

    enum class State { IDLE, LISTENING, DETECTED }

    private val wakeWords = listOf("света", "светочка", "светлана")

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
                } catch (t: Throwable) {
                    Log.w(TAG, "Цикл wake word прерван", t)
                    delay(2000)
                }
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        _state.value = State.IDLE
        recognizer.stopListening()
    }

    /**
     * Проверить, содержит ли фраза слово пробуждения.
     * Возвращает команду без wake word или null.
     */
    fun matchWakeWord(phrase: String): String? {
        val low = phrase.lowercase().trim().replace("ё", "е")
        val matched = wakeWords.firstOrNull { low == it || low.startsWith("$it ") || low.startsWith("$it,") }
        if (matched == null) return null
        _state.value = State.DETECTED
        return low.removePrefix(matched).trimStart(' ', ',').ifBlank { null }
    }

    companion object { private const val TAG = "WakeWord" }
}
