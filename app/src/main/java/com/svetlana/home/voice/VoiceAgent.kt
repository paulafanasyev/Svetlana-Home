package com.svetlana.home.voice

import android.content.Context
import com.svetlana.home.R
import com.svetlana.home.ai.AIMode
import com.svetlana.home.ai.AIRouter
import com.svetlana.home.ai.AIModelCompatibilityEngine
import com.svetlana.home.ai.AIModelRegistry
import com.svetlana.home.control.ActionRouter
import com.svetlana.home.control.SvetlanaAction
import com.svetlana.home.device.DeviceCapabilityManager
import com.svetlana.home.memory.HistoryCategory
import com.svetlana.home.memory.HistoryManager
import com.svetlana.home.memory.PersonalMemory
import com.svetlana.home.store.SettingsRepository

/**
 * VoiceAgent — единое ядро диалога для экрана и фонового сервиса.
 *
 * Цепочка (ТЗ §18, §56):
 *   ввод → Action Router (управление устройством) → AI-команды → AI-чат → голос.
 *
 * Используется и HomeViewModel (UI), и VoiceAssistantService (фон), поэтому
 * поведение диалога одинаково в обоих режимах: те же команды, тот же
 * privacy-роутинг, тот же голосовой отклик.
 */
class VoiceAgent(
    private val context: Context,
    private val actionRouter: ActionRouter,
    private val aiRouter: AIRouter,
    private val historyManager: HistoryManager,
    private val personalMemory: PersonalMemory,
    private val settings: SettingsRepository,
    private val compatibility: AIModelCompatibilityEngine,
    private val modelRegistry: AIModelRegistry,
    private val device: DeviceCapabilityManager
) {

    sealed class Outcome {
        /** Светлана ответила / выполнила команду. */
        data class Replied(val text: String, val actionName: String? = null) : Outcome()
        /** Требуется явное подтверждение пользователя (ТЗ §58). */
        data class Confirmation(val action: SvetlanaAction, val message: String) : Outcome()
    }

    /**
     * Последний ответ/событие диалога.
     *
     * Фоновый сервис — единственный обработчик wake word, чтобы команда не
     * выполнялась дважды. Главный экран наблюдает этот поток, чтобы
     * показывать ответы Светланы на экране, когда приложение открыто.
     */
    sealed class DialogueEvent {
        data class Reply(val text: String) : DialogueEvent()
        data class ConfirmationRequired(val action: SvetlanaAction, val message: String) : DialogueEvent()
    }

    private val _events = kotlinx.coroutines.flow.MutableSharedFlow<DialogueEvent>(extraBufferCapacity = 8)
    val events: kotlinx.coroutines.flow.SharedFlow<DialogueEvent> = _events

    /**
     * Filler-фразу говорим СРАЗУ при обращении к ИИ, чтобы пользователь
     * слышал отклик до того, как придёт полный ответ.
     * Требование: «сразу начинало говорить, чтобы пользователь понимал,
     * что ответ от ИИ есть».
     */
    private val fillers = listOf("Дай подумать…", "Сейчас…", "Минуточку…", "Так…")
    private var fillerIndex = 0

    /**
     * Обработать команду: текстом или голосом.
     *
     * @param speak озвучивать ли результат через TTS.
     * @param tts синтезатор речи (null — тишина, только текст).
     * @param onPartial колбэк с накопленным текстом ответа (для UI/стриминга).
     */
    suspend fun handle(
        text: String,
        speak: Boolean = true,
        tts: SvetlanaTts? = null,
        onPartial: ((String) -> Unit)? = null
    ): Outcome {
        if (text.isBlank()) return Outcome.Replied("")
        historyManager.record(HistoryCategory.COMMANDS, "Ввод: ${text.take(120)}")

        // 1. Команды управления устройством, включая длинные Hands-цепочки
        // (открой приложение → найди контакт → напиши → отправь).
        val actionResult = actionRouter.route(text)
        if (actionResult != null) {
            return if (actionResult.requiresUserConfirmation) {
                historyManager.record(HistoryCategory.CONFIRMATIONS,
                    "Запрошено подтверждение: ${actionResult.action.name}")
                if (speak) tts?.speak(actionResult.message)
                _events.tryEmit(DialogueEvent.ConfirmationRequired(actionResult.action, actionResult.message))
                Outcome.Confirmation(actionResult.action, actionResult.message)
            } else {
                if (speak) tts?.speak(actionResult.message)
                _events.tryEmit(DialogueEvent.Reply(actionResult.message))
                Outcome.Replied(actionResult.message, actionResult.action.name)
            }
        }

        // 2. Команды выбора ИИ (ТЗ §48)
        val aiCommand = handleAiCommand(text)
        if (aiCommand != null) {
            if (speak) tts?.speak(aiCommand)
            _events.tryEmit(DialogueEvent.Reply(aiCommand))
            return Outcome.Replied(aiCommand)
        }

        // 3. Запрос к моделям. Filler говорим СРАЗУ — до получения ответа.
        if (speak) speakFiller(tts)

        val memory = personalMemory.context()
        val prompt = if (memory.isNotBlank()) "$memory\n\n$text" else text

        val reply = StringBuilder()
        var success = true
        aiRouter.chatStream(prompt).collect { chunk ->
            if (chunk.full.isNotBlank()) {
                reply.clear()
                reply.append(chunk.full)
                onPartial?.invoke(chunk.full)
            }
            if (chunk.done) success = chunk.success
        }
        val finalText = reply.toString()
            .ifBlank { if (success) context.getString(R.string.reply_empty) else context.getString(R.string.reply_provider_failed) }

        // Говорим ответ: flush=true прерывает filler, и сразу идёт суть.
        if (speak) tts?.speak(finalText)
        if (success) _events.tryEmit(DialogueEvent.Reply(finalText))
        return Outcome.Replied(finalText)
    }

    private fun speakFiller(tts: SvetlanaTts?) {
        val phrase = fillers[fillerIndex % fillers.size]
        fillerIndex++
        // flush = false: filler ставится в очередь, а затем перебивается
        // реальным ответом (flush=true), как только он готов.
        tts?.speak(phrase, flush = false)
    }

    private suspend fun handleAiCommand(text: String): String? {
        val low = text.lowercase().trim().replace("ё", "е")
        return when {
            low.contains("только локальный") || low.contains("только устройство") -> {
                settings.setAiMode(AIMode.LOCAL_ONLY)
                "Хорошо, теперь используется только локальный ИИ. Ничего не отправляется в облако."
            }
            low.contains("мой сервер") -> {
                settings.setAiMode(AIMode.MY_SERVER)
                "Хорошо, теперь используется ваш сервер."
            }
            low.contains("локальный в приоритете") -> {
                settings.setAiMode(AIMode.LOCAL_FIRST)
                "Хорошо, локальный ИИ в приоритете."
            }
            low.contains("внешнего провайдера") || low.contains("openai-compatible") -> {
                settings.setAiMode(AIMode.EXTERNAL)
                "Хорошо, используется внешний провайдер."
            }
            low.contains("не отправляй") && low.contains("облако") -> {
                settings.setAiMode(AIMode.LOCAL_ONLY)
                "Хорошо, данные не будут покидать устройство."
            }
            low.contains("какой ии") || low.contains("какая модель") || low.contains("кто отвечает") -> {
                aiRouter.currentBackendLabel()
            }
            low.contains("какой ии может работать") || low.contains("какие модели") ||
                    low.contains("подходящие варианты") -> {
                offerModels()
            }
            else -> null
        }
    }

    /**
     * ТЗ §64: предложить модели, ничего не скачивая.
     */
    private suspend fun offerModels(): String {
        val report = compatibility.bestFit(modelRegistry)
            ?: return "Я не нашла модели, совместимой с этим устройством."
        val caps = device.current()
        return buildString {
            append("Я нашла локальную модель, совместимую с вашим устройством.\n\n")
            append("Название: ${report.model.name}\n")
            append("Размер: ${report.model.sizeMb} МБ\n")
            append("Требуется памяти: ${report.model.ramRequirementMb} МБ (на устройстве ${caps.ramTotalMb} МБ)\n")
            append("Ожидаемая производительность: ${report.expectedPerf}\n\n")
            append("Скачать можно в разделе «Локальный ИИ». Я ничего не скачиваю без вашего решения.")
        }
    }
}
