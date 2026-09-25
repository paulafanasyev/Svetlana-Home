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

    private val _speaking = MutableStateFlow(false)
    val speaking: StateFlow<Boolean> = _speaking.asStateFlow()

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
        } else {
            ready = false
        }
    }

    val isAvailable: Boolean get() = ready && tts != null

    /**
     * Озвучить текст на русском.
     */
    fun speak(text: String, flush: Boolean = true) {
        if (!isAvailable || text.isBlank()) return
        try {
            tts?.speak(text, if (flush) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, null, "svetlana_${System.currentTimeMillis()}")
        } catch (t: Throwable) {
            Log.w(TAG, "Не удалось озвучить текст", t)
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
