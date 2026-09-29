package com.svetlana.home.voice

import android.content.Context
import android.content.Intent
import android.os.Build
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
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale

/**
 * SvetlanaSpeechRecognizer — русский STT.
 *
 * Аудит §16: обычный SpeechRecognizer не гарантирует, что распознавание
 * происходит на устройстве — системный сервис может уходить в сеть.
 * Начиная с API 31 Android предоставляет createOnDeviceSpeechRecognizer(),
 * который гарантированно работает локально. Если он доступен —
 * используется именно он. Иначе честно падаем на системный распознаватель,
 * не заявляя «голос никогда не покидает устройство».
 *
 * Аудит §18: жизненный цикл сессии теперь явный (VoiceSessionStateMachine).
 * WakeWordEngine не может стартовать слушание, пока сессия пользователя
 * не вернулась в IDLE — это убирает гонку «новая сессия убила старую».
 *
 * Если на устройстве нет распознавателя (например, без GMS), голосовой ввод
 * честно сообщается как недоступный — приложение не падает.
 */
class SvetlanaSpeechRecognizer(private val context: Context) {

    private var recognizer: SpeechRecognizer? = null
    @Volatile
    private var startGeneration: Long = 0L
    @Volatile
    private var activeSessionGeneration: Long? = null
    // SpeechRenderer требует main-thread Looper. WakeWordEngine и другие
    // фоновые корутины могут вызывать startListening() не из main thread —
    // поэтому все операции с recogniser'ом выполняем через главный Handler.
    private val mainHandler = Handler(Looper.getMainLooper())
    private fun onMain(action: () -> Unit) {
        if (Looper.myLooper() === Looper.getMainLooper()) action()
        else mainHandler.post { action() }
    }

    /** Аудит §18: явное состояние сессии. */
    val session = VoiceSessionStateMachine()

    private val _partial = MutableStateFlow("")
    val partial: StateFlow<String> = _partial.asStateFlow()

    private val _result = MutableStateFlow<SttResult?>(null)
    val result: StateFlow<SttResult?> = _result.asStateFlow()

    private val _listening = MutableStateFlow(false)
    val listening: StateFlow<Boolean> = _listening.asStateFlow()

    /**
     * Аудит §16: действительно ли доступен on-device (офлайн) распознаватель.
     * Только в этом случае можно заявлять «голос не покидает устройство».
     */
    val isOnDeviceAvailable: Boolean
        get() = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
            } else false
        } catch (t: Throwable) { false }

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
        // Аудит §18: не стартуем новую сессию поверх активной —
        // это была причина потери результата пользователя.
        if (session.isUserSessionActive) {
            Log.w(TAG, "startListening отклонён: сессия уже активна (${session.state})")
            return
        }
        stopListening()
        clearResult()
        if (!isAvailable) {
            _result.value = SttResult.Error("Распознавание речи недоступно на этом устройстве")
            return
        }
        _foregroundSession.value = true
        session.transitionTo(VoiceSessionState.STARTING)
        val generation = startGeneration + 1L
        startGeneration = generation
        try {
            onMain {
                // stopListening()/finishSession() могут быть вызваны до выполнения
                // этого runnable. Не разрешаем отложенному старту воскресить сессию.
                if (generation != startGeneration || !session.isUserSessionActive) return@onMain
                activeSessionGeneration = generation
                // Аудит §16: предпочитаем гарантированно on-device распознаватель.
                recognizer = createRecognizer().apply {
                    setRecognitionListener(createListener(generation))
                }
                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, locale.toLanguageTag())
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                }
                recognizer?.startListening(intent)
                session.transitionTo(VoiceSessionState.LISTENING)
                _listening.value = true
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Не удалось начать распознавание", t)
            session.transitionTo(VoiceSessionState.STOPPED)
            _result.value = SttResult.Error(t.message ?: "Ошибка распознавания")
        }
    }

    /**
     * Аудит §16: on-device распознаватель, если устройство его поддерживает.
     * Это единственный способ гарантировать, что аудио не уходит в сеть.
     */
    private fun createRecognizer(): SpeechRecognizer =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && isOnDeviceAvailable) {
            Log.i(TAG, "Используется on-device (офлайн) распознаватель")
            SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        } else {
            Log.i(TAG, "On-device распознаватель недоступен — системный (может использовать сеть)")
            SpeechRecognizer.createSpeechRecognizer(context)
        }

    /**
     * Мягкий стоп: завершаем слушание, но НЕ уничтожаем распознаватель.
     *
     * Аудит п.11: немедленный cancel()+destroy() после stopListening() мог
     * отменить выдачу финального результата — onResults() не вызывался,
     * и wake word получал пустой ответ. Сначала даём системе
     * отдать результат, уничтожаем только в release().
     */
    /**
     * Завершает текущую STT-сессию после обработки результата.
     *
     * Без явного завершения VoiceSessionStateMachine могла навсегда остаться
     * в PROCESSING после onResults() или в STOPPED после ошибки, блокируя
     * последующий startListening().
     */
    fun finishSession() {
        _foregroundSession.value = false
        _listening.value = false
        startGeneration += 1L
        activeSessionGeneration = null
        onMain {
            try {
                recognizer?.cancel()
                recognizer?.destroy()
            } catch (_: Throwable) { }
            recognizer = null
        }
        session.finish()
    }

    /**
     * Ожидает терминальный результат текущей STT-сессии.
     *
     * Используется вместо polling по StateFlow.value: финальный onResults/onError
     * может прийти асинхронно после stopListening(). Таймаут оставляет системе
     * возможность завершить сессию, но не блокирует вызывающий coroutine.
     */
    suspend fun awaitResult(timeoutMs: Long): SttResult? =
        withTimeoutOrNull(timeoutMs.coerceAtLeast(1L)) {
            result.filterNotNull().first()
        }

    fun stopListening() {
        // Invalidates only a not-yet-executed start runnable. The active session
        // generation remains valid so a terminal onResults()/onError() can still
        // be delivered after stopListening().
        startGeneration += 1L
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
        startGeneration += 1L
        activeSessionGeneration = null
        try {
            onMain {
                recognizer?.cancel()
                recognizer?.destroy()
                recognizer = null
            }
        } catch (t: Throwable) { /* ignore */ }
        _listening.value = false
        _foregroundSession.value = false
        // Аудит §18: гарантированное освобождение — сессия снова доступна.
        session.transitionTo(VoiceSessionState.STOPPED)
        session.finish()
    }

    private fun createListener(generation: Long): RecognitionListener =
        object : RecognitionListener {
            private fun current(): Boolean = activeSessionGeneration == generation

            override fun onReadyForSpeech(params: Bundle?) {
                if (!current()) return
                session.transitionTo(VoiceSessionState.LISTENING)
                _listening.value = true
            }

            override fun onBeginningOfSpeech() {
                if (!current()) return
            }

            override fun onRmsChanged(rmsdB: Float) {
                if (!current()) return
            }

            override fun onBufferReceived(buffer: ByteArray?) {
                if (!current()) return
            }

            override fun onEndOfSpeech() {
                if (!current()) return
                _listening.value = false
            }

            override fun onError(error: Int) {
                if (!current()) return
                _listening.value = false
                session.transitionTo(VoiceSessionState.STOPPED)
                _result.value = SttResult.Error(errorText(error))
            }

            override fun onResults(results: Bundle?) {
                if (!current()) return
                _listening.value = false
                // Аудит §18: только финальный результат завершает этап распознавания.
                session.transitionTo(VoiceSessionState.RESULT_RECEIVED)
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                _result.value = SttResult.Success(matches?.firstOrNull().orEmpty())
                session.transitionTo(VoiceSessionState.PROCESSING)
            }

            override fun onPartialResults(partialResults: Bundle?) {
                if (!current()) return
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
