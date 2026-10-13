package com.svetlana.home.voice

import android.content.Context
import com.svetlana.home.R
import com.svetlana.home.ai.AIMode
import com.svetlana.home.ai.AIRouter
import com.svetlana.home.ai.AIModelCompatibilityEngine
import com.svetlana.home.ai.AIModelRegistry
import com.svetlana.home.ai.label
import com.svetlana.home.control.ActionRouter
import com.svetlana.home.control.SvetlanaAction
import com.svetlana.home.core.Tr
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
 * меняются, личная память в запрос не добавляется ([VoicePolicy]). Правила одинаковы
 * для русских и английских команд; ответы — на языке интерфейса.
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

    private val fillersRu = listOf("Дай подумать…", "Сейчас…", "Минуточку…", "Так…")
    private val fillersEn = listOf("Let me think…", "One moment…", "Just a second…", "Okay…")
    private var fillerIndex = 0

    private fun english(): Boolean = runCatching { Tr.isEnglish(context) }.getOrDefault(false)
    private fun t(ru: String, en: String): String = if (english()) en else ru

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
        val list = if (english()) fillersEn else fillersRu
        val phrase = list[fillerIndex % list.size]
        fillerIndex++
        tts?.speak(phrase, flush = false)
    }

    private fun hasAny(low: String, vararg needles: String) = needles.any { low.contains(it) }

    private suspend fun handleAiCommand(text: String, mayChange: Boolean): String? {
        val low = text.lowercase().trim().replace("ё", "е").replace('’', '\'')
        val noCloudEn = hasAny(low, "don't send", "do not send", "dont send") && low.contains("cloud")
        val change: AIMode? = when {
            low.contains("только локальный") || low.contains("только устройство") -> AIMode.LOCAL_ONLY
            low.contains("мой сервер") -> AIMode.MY_SERVER
            low.contains("локальный в приоритете") -> AIMode.LOCAL_FIRST
            low.contains("внешнего провайдера") || low.contains("openai-compatible") -> AIMode.EXTERNAL
            low.contains("не отправляй") && low.contains("облако") -> AIMode.LOCAL_ONLY
            // English
            hasAny(low, "only local ai", "local ai only", "only the local ai", "device only mode", "use device only") -> AIMode.LOCAL_ONLY
            hasAny(low, "use my server", "switch to my server") -> AIMode.MY_SERVER
            hasAny(low, "local first") -> AIMode.LOCAL_FIRST
            hasAny(low, "use external provider", "use the external provider", "use an external provider",
                "switch to external provider", "switch to the external provider") -> AIMode.EXTERNAL
            noCloudEn -> AIMode.LOCAL_ONLY
            else -> null
        }
        if (change != null) {
            if (!mayChange) return VoicePolicy.settingsRefused(english())
            settings.setAiMode(change)
            val cloud = low.contains("облако") || noCloudEn
            return when (change) {
                AIMode.LOCAL_ONLY -> if (cloud) t("Хорошо, данные не будут покидать устройство.", "Okay, your data won't leave the device.")
                    else t("Хорошо, теперь используется только локальный ИИ. Ничего не отправляется в облако.",
                        "Okay, now only the local AI is used. Nothing is sent to the cloud.")
                AIMode.MY_SERVER -> t("Хорошо, теперь используется ваш сервер.", "Okay, now your server is used.")
                AIMode.LOCAL_FIRST -> t("Хорошо, локальный ИИ в приоритете.", "Okay, the local AI comes first.")
                AIMode.EXTERNAL -> t("Хорошо, используется внешний провайдер.", "Okay, the external provider is used.")
                else -> t("Хорошо.", "Okay.")
            }
        }
        return when {
            low.contains("какой ии может работать") || low.contains("какие модели") ||
                low.contains("подходящие варианты") ||
                hasAny(low, "which ai can run", "what ai can run", "which models can run", "what models can run",
                    "compatible models", "which model can run") -> offerModels()
            low.contains("какой ии") || low.contains("какая модель") || low.contains("кто отвечает") ||
                hasAny(low, "which ai are you", "what ai are you", "which ai is answering", "what ai is answering",
                    "which model are you", "who is answering", "who's answering") ->
                aiRouter.currentBackend().label(context)
            else -> null
        }
    }

    private suspend fun offerModels(): String {
        val report = compatibility.bestFit(modelRegistry)
            ?: return t("Я не нашла модели, совместимой с этим устройством.",
                "I couldn't find a model compatible with this device.")
        val caps = device.current()
        if (english()) return buildString {
            append("I found a local model compatible with your device.\n\n")
            append("Name: ${report.model.name}\n")
            append("Size: ${report.model.sizeMb} MB\n")
            append("Memory needed: ${report.model.ramRequirementMb} MB (device has ${caps.ramTotalMb} MB)\n")
            append("Expected performance: ${report.expectedPerf}\n\n")
            append("You can download it in Local AI. I don't download anything without your decision.")
        }
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
