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
 * Безопасность: команда из фонового слова пробуждения ([CommandOrigin.BACKGROUND_VOICE]) не
 * аутентифицирована: любое действие подтверждается в открытом приложении, настройки ИИ не
 * меняются, личная память в запрос не добавляется ([VoicePolicy]).
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
        data class Replied(val text: String, val actionName: String? = null) : Outcome()
        data class Confirmation(val action: SvetlanaAction, val message: String) : Outcome()
    }

    sealed class DialogueEvent {
        data class Reply(val text: String) : DialogueEvent()
        data class ConfirmationRequired(val action: SvetlanaAction, val message: String) : DialogueEvent()
    }

    private val _events = kotlinx.coroutines.flow.MutableSharedFlow<DialogueEvent>(extraBufferCapacity = 8)
    val events: kotlinx.coroutines.flow.SharedFlow<DialogueEvent> = _events

    private val fillers = listOf("Дай подумать…", "Сейчас…", "Минуточку…", "Так…")
    private var fillerIndex = 0

    /**
     * Обработать команду: текстом или голосом.
     * @param origin откуда команда: из открытого приложения или от фонового слова пробуждения.
     */
    suspend fun handle(
        text: String,
        speak: Boolean = true,
        tts: SvetlanaTts? = null,
        onPartial: ((String) -> Unit)? = null,
        origin: CommandOrigin = CommandOrigin.TEXT
    ): Outcome {
        if (text.isBlank()) return Outcome.Replied("")
        historyManager.record(HistoryCategory.COMMANDS,
            (if (origin == CommandOrigin.BACKGROUND_VOICE) "Голос (фон): " else "Ввод: ") + text.take(120))

        // 1. Команды управления устройством.
        val actionResult = actionRouter.route(text, unattendedVoice = VoicePolicy.mustConfirmInApp(origin))
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

        // 2. Команды выбора ИИ (ТЗ §48) — меняют настройки приватности, поэтому не из фона.
        val aiCommand = handleAiCommand(text, VoicePolicy.mayChangeSettings(origin))
        if (aiCommand != null) {
            if (speak) tts?.speak(aiCommand)
            _events.tryEmit(DialogueEvent.Reply(aiCommand))
            return Outcome.Replied(aiCommand)
        }

        // 3. Запрос к моделям. Filler говорим СРАЗУ — до получения ответа.
        if (speak) speakFiller(tts)

        // Личная память — только для команд из открытого приложения: чужой голос рядом не услышит ваши данные.
        val memory = if (VoicePolicy.mayUsePersonalMemory(origin)) personalMemory.context() else ""
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

        if (speak) tts?.speak(finalText)
        if (success) _events.tryEmit(DialogueEvent.Reply(finalText))
        return Outcome.Replied(finalText)
    }

    private fun speakFiller(tts: SvetlanaTts?) {
        val phrase = fillers[fillerIndex % fillers.size]
        fillerIndex++
        tts?.speak(phrase, flush = false)
    }

    private suspend fun handleAiCommand(text: String, mayChange: Boolean): String? {
        val low = text.lowercase().trim().replace("ё", "е")
        val change: AIMode? = when {
            low.contains("только локальный") || low.contains("только устройство") -> AIMode.LOCAL_ONLY
            low.contains("мой сервер") -> AIMode.MY_SERVER
            low.contains("локальный в приоритете") -> AIMode.LOCAL_FIRST
            low.contains("внешнего провайдера") || low.contains("openai-compatible") -> AIMode.EXTERNAL
            low.contains("не отправляй") && low.contains("облако") -> AIMode.LOCAL_ONLY
            else -> null
        }
        if (change != null) {
            if (!mayChange) return VoicePolicy.SETTINGS_REFUSED
            settings.setAiMode(change)
            return when (change) {
                AIMode.LOCAL_ONLY -> if (low.contains("облако")) "Хорошо, данные не будут покидать устройство."
                    else "Хорошо, теперь используется только локальный ИИ. Ничего не отправляется в облако."
                AIMode.MY_SERVER -> "Хорошо, теперь используется ваш сервер."
                AIMode.LOCAL_FIRST -> "Хорошо, локальный ИИ в приоритете."
                AIMode.EXTERNAL -> "Хорошо, используется внешний провайдер."
                else -> "Хорошо."
            }
        }
        return when {
            low.contains("какой ии может работать") || low.contains("какие модели") ||
                low.contains("подходящие варианты") -> offerModels()
            low.contains("какой ии") || low.contains("какая модель") || low.contains("кто отвечает") ->
                aiRouter.currentBackendLabel()
            else -> null
        }
    }

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
