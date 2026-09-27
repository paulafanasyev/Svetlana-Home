package com.svetlana.home.voice

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

/**
 * SvetlanaTts — русский синтез речи через системный TTS Android.
 */
class SvetlanaTts(context: Context) : TextToSpeech.OnInitListener {

    private var tts: TextToSpeech? = null
    private var ready = false

    /**
     * Аудит п.12: TTS инициализируется асинхронно. Если пользователь
     * отправит команду до завершения onInit(), фраза молча терялась.
     * Теперь ждём готовности и затем проигрываем очередь.
     */
    private val pending = mutableListOf<Pair<String, java.util.Locale?>>()
    private val lock = Any()

    private val _speaking = MutableStateFlow(false)
    val speaking: StateFlow<Boolean> = _speaking.asStateFlow()

    enum class TtsState { INITIALIZING, READY, UNAVAILABLE }
    private val _state = MutableStateFlow(TtsState.INITIALIZING)
    val state: StateFlow<TtsState> = _state.asStateFlow()

    init {
        try {
            tts = TextToSpeech(context.applicationContext, this)
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) { _speaking.value = true }
                override fun onDone(utteranceId: String?) { _speaking.value = false }
                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) { _speaking.value = false }
            })
        } catch (t: Throwable) {
            Log.w(TAG, "TTS недоступен", t)
            _state.value = TtsState.UNAVAILABLE
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val result = tts?.setLanguage(Locale("ru", "RU"))
            ready = result != TextToSpeech.LANG_MISSING_DATA && result != TextToSpeech.LANG_NOT_SUPPORTED
            if (!ready) {
                // Запасной язык
                tts?.setLanguage(Locale.getDefault())
                ready = true
            }
            _state.value = TtsState.READY
            // Проигрываем всё, что накопилось во время инициализации.
            synchronized(lock) {
                pending.forEach { (text, locale) ->
                    if (locale != null) speakInternal(text, locale)
                    else speakInternal(text)
                }
                pending.clear()
            }
        } else {
            ready = false
            _state.value = TtsState.UNAVAILABLE
        }
    }

    val isAvailable: Boolean get() = ready && tts != null

    /**
     * Озвучить текст на русском.
     */
    fun speak(text: String, flush: Boolean = true) {
        if (text.isBlank()) return
        if (!isAvailable) {
            // Аудит п.12: ещё инициализируется — поставим в очередь, а не
            // молча выбросим.
            if (_state.value == TtsState.INITIALIZING) {
                synchronized(lock) { pending.add(text to null) }
            } else {
                Log.w(TAG, "TTS не готов, фраза утеряна: ${text.take(40)}")
            }
            return
        }
        speakInternal(text, flush)
    }

    private fun speakInternal(text: String, flush: Boolean = true) {
        try {
            tts?.speak(text, if (flush) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, null, "svetlana_${System.currentTimeMillis()}")
        } catch (t: Throwable) {
            Log.w(TAG, "Не удалось озвучить текст", t)
        }
    }

    /**
     * Озвучить текст на указанном языке (ТЗ §53: перевод озвучивается
     * на целевом языке — RU→VI говорит по-вьетнамски и наоборот).
     */
    fun speak(text: String, locale: java.util.Locale, flush: Boolean = true) {
        if (text.isBlank()) return
        if (!isAvailable) {
            if (_state.value == TtsState.INITIALIZING) {
                synchronized(lock) { pending.add(text to locale) }
            } else {
                Log.w(TAG, "TTS не готов, фраза утеряна: ${text.take(40)}")
            }
            return
        }
        speakInternal(text, locale)
    }

    private fun speakInternal(text: String, locale: java.util.Locale, flush: Boolean = true) {
        try {
            // Переключаем язык только если он поддерживается; иначе остаётся
            // язык по умолчанию (лучше сказать с акцентом, чем промолчать).
            val res = tts?.setLanguage(locale)
            if (res == TextToSpeech.LANG_MISSING_DATA || res == TextToSpeech.LANG_NOT_SUPPORTED) {
                Log.w(TAG, "Язык ${locale.toLanguageTag()} не поддерживается TTS, использую по умолчанию")
            }
            tts?.speak(text, if (flush) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD,
                null, "svetlana_${System.currentTimeMillis()}")
            // Возвращаем русский как основной язык интерфейса
            tts?.setLanguage(Locale("ru", "RU"))
        } catch (t: Throwable) {
            Log.w(TAG, "Не удалось озвучить текст на ${locale.toLanguageTag()}", t)
        }
    }

    fun speakAsync(text: String) {
        speak(text, flush = false)
    }

    fun stop() {
        try { tts?.stop() } catch (t: Throwable) { /* ignore */ }
        _speaking.value = false
    }

    fun shutdown() {
        try { tts?.shutdown() } catch (t: Throwable) { /* ignore */ }
        tts = null
        ready = false
    }

    companion object { private const val TAG = "SvetlanaTTS" }
}
