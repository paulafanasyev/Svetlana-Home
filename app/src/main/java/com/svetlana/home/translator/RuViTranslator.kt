package com.svetlana.home.translator

import com.google.android.gms.tasks.Tasks
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.TranslateRemoteModel
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * On-device RU <-> VI translation.
 *
 * Model download is always explicit: this class never downloads a translation
 * model from translate(). Download is performed only from downloadModel(),
 * which uses Wi-Fi by default.
 */
enum class TranslatorDirection(
    val sourceLanguage: String,
    val targetLanguage: String,
    val sourceLocale: Locale,
    val targetLocale: Locale,
    val label: String
) {
    RU_TO_VI(
        TranslateLanguage.RUSSIAN,
        TranslateLanguage.VIETNAMESE,
        Locale.forLanguageTag("ru-RU"),
        Locale.forLanguageTag("vi-VN"),
        "Русский → Tiếng Việt"
    ),
    VI_TO_RU(
        TranslateLanguage.VIETNAMESE,
        TranslateLanguage.RUSSIAN,
        Locale.forLanguageTag("vi-VN"),
        Locale.forLanguageTag("ru-RU"),
        "Tiếng Việt → Русский"
    )
}

class RuViTranslator { 
    private val modelManager: RemoteModelManager = RemoteModelManager.getInstance()

    suspend fun isModelDownloaded(direction: TranslatorDirection): Boolean =
        withContext(Dispatchers.IO) {
            Tasks.await(modelManager.isModelDownloaded(remoteModel(direction)))
        }

    suspend fun downloadModel(
        direction: TranslatorDirection,
        wifiOnly: Boolean = true
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val builder = DownloadConditions.Builder()
            if (wifiOnly) builder.requireWifi()
            Tasks.await(modelManager.download(remoteModel(direction), builder.build()))
            Unit
        }
    }

    suspend fun deleteModel(direction: TranslatorDirection): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                Tasks.await(modelManager.deleteDownloadedModel(remoteModel(direction)))
                Unit
            }
        }

    /**
     * Translates only when the requested target-language model already exists.
     * This prevents an accidental network/model download during translation.
     */
    suspend fun translate(
        text: String,
        direction: TranslatorDirection
    ): Result<String> = withContext(Dispatchers.IO) {
        val input = text.trim()
        if (input.isBlank()) {
            return@withContext Result.failure(IllegalArgumentException("Пустой текст"))
        }

        runCatching {
            if (!Tasks.await(modelManager.isModelDownloaded(remoteModel(direction)))) {
                throw ModelNotDownloadedException(
                    "Модель перевода " + direction.label + " не скачана. Нажмите «Скачать модель»."
                )
            }

            val options = TranslatorOptions.Builder()
                .setSourceLanguage(direction.sourceLanguage)
                .setTargetLanguage(direction.targetLanguage)
                .build()

            val translator: Translator = Translation.getClient(options)
            try {
                Tasks.await(translator.translate(input))
            } finally {
                translator.close()
            }
        }
    }

    private fun remoteModel(direction: TranslatorDirection): TranslateRemoteModel =
        TranslateRemoteModel.Builder(direction.targetLanguage).build()

    class ModelNotDownloadedException(message: String) : IllegalStateException(message)
}
