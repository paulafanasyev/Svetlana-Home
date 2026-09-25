package com.svetlana.home.translate

import android.content.Context
import android.util.Log
import com.svetlana.home.ai.AIBackend
import com.svetlana.home.core.ServiceLocator
import com.svetlana.home.memory.HistoryCategory
import com.svetlana.home.memory.HistoryManager
import com.svetlana.home.voice.SvetlanaSpeechRecognizer
import com.svetlana.home.voice.SvetlanaTts
import kotlinx.coroutines.flow.first

/**
 * SvetlanaTranslator — RU ↔ VI переводчик (ТЗ §52, §53).
 *
 * Режимы:
 *  - текст;
 *  - голос: STT → перевод → TTS;
 *  - синхронный режим разговора с автоопределением языка;
 *  - перевод через камеру (CameraTranslateActivity + Vision).
 */
class SvetlanaTranslator(
    private val context: Context,
    private val providerManager: TranslatorProviderManager,
    private val tts: SvetlanaTts,
    private val recognizer: SvetlanaSpeechRecognizer
) {

    suspend fun translateText(text: String, directionCode: String): TranslateResult {
        val direction = TranslateDirection.fromCode(directionCode) ?: TranslateDirection.RU_TO_VI
        val backend = ServiceLocator.settings.translatorBackend.first()
        val result = providerManager.translate(text, direction,
            if (backend == "auto") null else backend)
        ServiceLocator.historyManager.record(
            HistoryCategory.TRANSLATE,
            "${direction.label}: ${result.sourceText.take(48)} → ${result.text.take(48)} (${result.backend})"
        )
        return result
    }

    suspend fun translateText(text: String, direction: TranslateDirection): TranslateResult =
        translateText(text, direction.code)

    /**
     * Голосовой перевод: распознавание → перевод → синтез.
     */
    suspend fun translateVoice(direction: TranslateDirection): TranslateResult {
        if (!recognizer.isAvailable) {
            return TranslateResult(false, "", "", direction, "voice",
                "Распознавание речи недоступно на этом устройстве")
        }
        val locale = if (direction == TranslateDirection.RU_TO_VI)
            java.util.Locale("ru", "RU") else java.util.Locale("vi", "VN")
        recognizer.startListening(locale)
        val result = waitForSttResult()
        recognizer.stopListening()
        if (result == null) {
            return TranslateResult(false, "", "", direction, "voice", "Речь не распознана")
        }
        val translated = translateText(result, direction)
        if (translated.success && tts.isAvailable) {
            tts.speak(translated.text)
        }
        return translated
    }

    private suspend fun waitForSttResult(timeoutMs: Long = 8000L): String? {
        val deadline = System.currentTimeMillis() + timeoutMs
        var last: SvetlanaSpeechRecognizer.SttResult? = null
        while (System.currentTimeMillis() < deadline) {
            val current = recognizer.result.value
            if (current != null && current !is SvetlanaSpeechRecognizer.SttResult.Success) {
                return null
            }
            if (current is SvetlanaSpeechRecognizer.SttResult.Success) {
                return current.text
            }
            kotlinx.coroutines.delay(200)
        }
        return last?.let { null }
    }

    /**
     * Синхронный режим: автоопределение говорящего и языка (ТЗ §53).
     * Определение делается по словарю: если в фразе преобладают кириллические
     * символы — говорящий по-русски, иначе — вьетнамский.
     */
    suspend fun translateConversationCycle(): TranslateResult {
        // Слушаем любой из двух языков
        recognizer.startListening(java.util.Locale("ru", "RU"))
        val spoken = waitForSttResult()
        recognizer.stopListening()
        if (spoken.isNullOrBlank()) {
            return TranslateResult(false, "", "", TranslateDirection.RU_TO_VI, "voice", "Речь не распознана")
        }
        val isRussian = detectRussian(spoken)
        val direction = if (isRussian) TranslateDirection.RU_TO_VI else TranslateDirection.VI_TO_RU
        val translated = translateText(spoken, direction)
        if (translated.success && tts.isAvailable) tts.speak(translated.text)
        return translated
    }

    fun detectRussian(text: String): Boolean {
        val cyrillic = text.count { it in 'А'..'я' || it == 'ё' || it == 'Ё' }
        val latin = text.count { it in 'A'..'z' }
        return cyrillic > latin
    }

    /**
     * Перевод текста с экрана / с изображения (камера) — используется Vision.
     */
    suspend fun translateImageText(ocrText: String, direction: TranslateDirection): TranslateResult =
        translateText(ocrText, direction)

    companion object { private const val TAG = "Translator" }
}
