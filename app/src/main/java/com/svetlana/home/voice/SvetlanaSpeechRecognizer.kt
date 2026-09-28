package com.svetlana.home.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

/**
 * SvetlanaSpeechRecognizer — русский STT через системный распознаватель Android.
 *
 * Если на устройстве нет распознавателя (например, без GMS), голосовой ввод
 * честно сообщается как недоступный — приложение не падает.
 */
class SvetlanaSpeechRecognizer(private val context: Context) {

    private var recognizer: SpeechRecognizer? = null
    // SpeechRenderer требует main-thread Looper. WakeWordEngine и другие
    // фоновые корутины могут вызывать startListening() не из main thread —
    // поэтому все операции с recogniser'ом выполняем через главный Handler.
    private val mainHandler = Handler(Looper.getMainLooper())
    private fun onMain(action: () -> Unit) {
        if (Looper.myLooper() === Looper.getMainLooper()) action()
        else mainHandler.post { action() }
    }

    private val _partial = MutableStateFlow("")
    val partial: StateFlow<String> = _partial.asStateFlow()

    private val _result = MutableStateFlow<SttResult?>(null)
    val result: StateFlow<SttResult?> = _result.asStateFlow()

    private val _listening = MutableStateFlow(false)
    val listening: StateFlow<Boolean> = _listening.asStateFlow()

    val isAvailable: Boolean
        get() = try {
            SpeechRecognizer.isRecognitionAvailable(context)
        } catch (t: Throwable) { false }

    /**
     * Активна ли ПРИОРИТЕТНАЯ сессия пользователя (тап по микрофону).
     *
     * Аудит-2026 проблема 1: фоновый цикл WakeWordEngine каждые ~4.4с
     * вызывал startListening(), который сначала делает stopListening() —
     * это уничтожало активную сессию пользователя. Теперь wake-word цикл
     * видит этот флаг и пропускает своё окно прослушивания.
     */
    private val _foregroundSession = MutableStateFlow(false)
    val foregroundSession: StateFlow<Boolean> = _foregroundSession.asStateFlow()

    /**
     * Очистка предыдущего результата (аудит п.9).
     *
     * Без этого waitForSttResult() мог получить СТАРЫЙ результат прошлой
     * сессии — например, повторный запрос получал предыдущую фразу.
     */
    fun clearResult() {
        _result.value = null
        _partial.value = ""
    }

    fun startListening(locale: Locale = Locale.forLanguageTag("ru-RU")) {
        stopListening()
        clearResult()
        if (!isAvailable) {
            _result.value = SttResult.Error("Распознавание речи недоступно на этом устройстве")
            return
        }
        _foregroundSession.value = true
        try {
            onMain {
                recognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
                    setRecognitionListener(listener)
                }
                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, locale.toLanguageTag())
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                }
                recognizer?.startListening(intent)
                _listening.value = true
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Не удалось начать распознавание", t)
            _result.value = SttResult.Error(t.message ?: "Ошибка распознавания")
        }
    }

    /**
     * Мягкий стоп: завершаем слушание, но НЕ уничтожаем распознаватель.
     *
     * Аудит п.11: немедленный cancel()+destroy() после stopListening() мог
     * отменить выдачу финального результата — onResults() не вызывался,
     * и wake word получал пустой ответ. Сначала даём системе
     * отдать результат, уничтожаем только в release().
     */
    fun stopListening() {
        try {
            onMain { recognizer?.stopListening() }
        } catch (t: Throwable) { /* ignore */ }
        _listening.value = false
        _foregroundSession.value = false
    }

    /**
     * Полное освобождение ресурсов. Вызывать после получения результата
     * или при выходе из экрана.
     */
    fun release() {
        try {
            onMain {
                recognizer?.cancel()
                recognizer?.destroy()
                recognizer = null
            }
        } catch (t: Throwable) { /* ignore */ }
        _listening.value = false
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) { _listening.value = true }
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() { _listening.value = false }
        override fun onError(error: Int) {
            _listening.value = false
            _result.value = SttResult.Error(errorText(error))
        }
        override fun onResults(results: Bundle?) {
            _listening.value = false
            val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            _result.value = SttResult.Success(matches?.firstOrNull().orEmpty())
        }
        override fun onPartialResults(partialResults: Bundle?) {
            val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            _partial.value = matches?.firstOrNull().orEmpty()
        }
        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    private fun errorText(error: Int): String = when (error) {
        SpeechRecognizer.ERROR_NO_MATCH -> "Речь не распознана"
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Время ожидания речи истекло"
        SpeechRecognizer.ERROR_AUDIO -> "Ошибка записи аудио"
        SpeechRecognizer.ERROR_NETWORK -> "Сетевая ошибка распознавания"
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Сетевой таймаут"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Нет разрешения на микрофон"
        else -> "Распознавание недоступно"
    }

    sealed class SttResult {
        data class Success(val text: String) : SttResult()
        data class Error(val message: String) : SttResult()
    }

    companion object { private const val TAG = "SvetlanaSTT" }
}
