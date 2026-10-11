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
 *
 * Безопасность: каждая фраза отмечается в [SpeechOutputGate] — фоновое слово пробуждения не слушает,
 * пока Светлана говорит, и отбрасывает окна записи, пересекающиеся с её речью.
 */
class SvetlanaTts(context: Context) : TextToSpeech.OnInitListener {

    private var tts: TextToSpeech? = null
    private var ready = false

    private data class PendingSpeech(val text: String, val locale: Locale, val flush: Boolean)
    private val pending = mutableListOf<PendingSpeech>()
    private val lock = Any()

    private val _speaking = MutableStateFlow(false)
    val speaking: StateFlow<Boolean> = _speaking.asStateFlow()

    enum class TtsState { INITIALIZING, READY, UNAVAILABLE }
    private val _state = MutableStateFlow(TtsState.INITIALIZING)
    val state: StateFlow<TtsState> = _state.asStateFlow()

    private fun finished() {
        SpeechOutputGate.ended()
        _speaking.value = SpeechOutputGate.isSpeaking
    }

    init {
        try {
            tts = TextToSpeech(context.applicationContext, this)
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) { _speaking.value = true }
                override fun onDone(utteranceId: String?) { finished() }
                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) { finished() }
                override fun onError(utteranceId: String?, errorCode: Int) { finished() }
                override fun onStop(utteranceId: String?, interrupted: Boolean) { finished() }
            })
        } catch (t: Throwable) {
            Log.w(TAG, "TTS недоступен", t)
            _state.value = TtsState.UNAVAILABLE
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val result = tts?.setLanguage(Locale.forLanguageTag("ru-RU"))
            ready = result != TextToSpeech.LANG_MISSING_DATA && result != TextToSpeech.LANG_NOT_SUPPORTED
            if (!ready) {
                tts?.setLanguage(Locale.getDefault())
                ready = true
            }
            _state.value = TtsState.READY
            synchronized(lock) {
                pending.forEach { item -> speakInternal(item.text, item.locale, item.flush) }
                pending.clear()
            }
        } else {
            ready = false
            _state.value = TtsState.UNAVAILABLE
        }
    }

    val isAvailable: Boolean get() = ready && tts != null

    fun speak(text: String, flush: Boolean = true) = speak(text, Locale.forLanguageTag("ru-RU"), flush)

    fun speak(text: String, locale: Locale, flush: Boolean = true) {
        if (text.isBlank()) return
        if (!isAvailable) {
            if (_state.value == TtsState.INITIALIZING) {
                synchronized(lock) { pending.add(PendingSpeech(text, locale, flush)) }
            } else {
                Log.w(TAG, "TTS не готов, фраза утеряна: ${text.take(40)}")
            }
            return
        }
        speakInternal(text, locale, flush)
    }

    private fun speakInternal(text: String, locale: Locale, flush: Boolean = true) {
        try {
            val language = tts?.setLanguage(locale)
            if (language == TextToSpeech.LANG_MISSING_DATA || language == TextToSpeech.LANG_NOT_SUPPORTED) {
                Log.w(TAG, "Язык TTS не поддерживается: ${locale.toLanguageTag()}")
                tts?.setLanguage(Locale.getDefault())
            }
            // Отмечаем ДО постановки в очередь: микрофон не должен открыться в паузе перед фразой.
            SpeechOutputGate.began()
            _speaking.value = true
            val code = tts?.speak(text, if (flush) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, null, "svetlana_${System.nanoTime()}")
            if (code != TextToSpeech.SUCCESS) finished()
        } catch (t: Throwable) {
            finished()
            Log.w(TAG, "Не удалось озвучить текст", t)
        }
    }

    fun speakAsync(text: String) {
        speak(text, flush = false)
    }

    fun stop() {
        try { tts?.stop() } catch (t: Throwable) { /* ignore */ }
        SpeechOutputGate.reset()
        _speaking.value = false
    }

    fun shutdown() {
        try { tts?.shutdown() } catch (t: Throwable) { /* ignore */ }
        SpeechOutputGate.reset()
        tts = null
        ready = false
    }

    companion object { private const val TAG = "SvetlanaTTS" }
}
