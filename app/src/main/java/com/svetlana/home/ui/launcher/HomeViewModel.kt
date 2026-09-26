package com.svetlana.home.ui.launcher

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.svetlana.home.R
import com.svetlana.home.ai.AIMode
import com.svetlana.home.avatar.AvatarLevel
import com.svetlana.home.control.ActionRouter
import com.svetlana.home.core.ServiceLocator
import com.svetlana.home.memory.HistoryCategory
import com.svetlana.home.translate.TranslateDirection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Состояния главного экрана.
 */
data class HomeUiState(
    val clock: String = "",
    val orbLevel: AvatarLevel = AvatarLevel.L0_LIVING_ORB,
    val orbReason: String = "",
    val orbActive: Boolean = false,
    val isListening: Boolean = false,
    val isThinking: Boolean = false,
    val isSpeaking: Boolean = false,
    val lastReply: String = "Чем помочь?",
    val partialInput: String = "",
    val aiBackendLabel: String = "",
    val pendingConfirmation: com.svetlana.home.control.SvetlanaAction? = null
)

class HomeViewModel : ViewModel() {

    private val _state = MutableStateFlow(HomeUiState())
    val state: StateFlow<HomeUiState> = _state.asStateFlow()

    fun refreshClock(context: Context) {
        val now = android.text.format.DateFormat.format("HH:mm", java.util.Date()).toString()
        _state.value = _state.value.copy(clock = now)
    }

    /**
     * ТЗ §21: слово пробуждения, обнаруженное WakeWordEngine в фоне.
     *
     * Цепочка: Микрофон → STT → WakeWordMatcher → DETECTED → команда → Action Router.
     * Аудит п.8: раньше результат STT никогда не читался, поэтому «Света»
     * физически не могла быть обнаружена. Теперь WakeWordEngine публикует
     * команду сюда, и она обрабатывается как обычный голосовой ввод.
     */
    fun observeWakeWord(context: Context) {
        viewModelScope.launch(Dispatchers.Default) {
            ServiceLocator.wakeWord.detection.collect { command ->
                if (command.isNullOrBlank()) return@collect
                ServiceLocator.wakeWord.consumeDetection()
                ServiceLocator.historyManager.record(
                    HistoryCategory.COMMANDS, "Wake word: ${command.take(120)}")
                handleInput(context, command)
            }
        }
    }

    fun refreshAvatarLevel() {
        val decision = ServiceLocator.avatarEngine.decide()
        _state.value = _state.value.copy(
            orbLevel = decision.selectedLevel,
            orbReason = if (decision.selectedLevel != decision.requestedLevel)
                decision.reason else ""
        )
    }

    suspend fun refreshBackendLabel() {
        val label = ServiceLocator.aiRouter.currentBackendLabel()
        _state.value = _state.value.copy(aiBackendLabel = label)
    }

    /**
     * Обработать команду пользователя: текстом или голосом.
     * Сначала — Action Router (управление устройством), потом — AI-чат.
     */
    fun handleInput(context: Context, text: String) {
        if (text.isBlank()) return
        ServiceLocator.historyManager.record(HistoryCategory.COMMANDS, "Ввод: ${text.take(120)}")
        _state.value = _state.value.copy(isThinking = true, lastReply = context.getString(R.string.home_thinking))

        viewModelScope.launch(Dispatchers.Default) {
            // 1. Команды управления устройством
            val router: ActionRouter = ServiceLocator.actionRouter
            val actionResult = router.route(text)
            if (actionResult != null) {
                val reply = actionResult.message
                _state.value = _state.value.copy(
                    isThinking = false,
                    lastReply = reply,
                    pendingConfirmation = if (actionResult.requiresUserConfirmation) actionResult.action else null
                )
                speak(context, reply)
                return@launch
            }

            // 2. Команды выбора ИИ
            val aiCommand = handleAiCommand(context, text)
            if (aiCommand != null) {
                _state.value = _state.value.copy(isThinking = false, lastReply = aiCommand)
                speak(context, aiCommand)
                refreshBackendLabel()
                return@launch
            }

            // 3. Запрос к моделям
            val memory = ServiceLocator.personalMemory.context()
            val prompt = if (memory.isNotBlank()) "$memory\n\n$text" else text
            val aiResult = ServiceLocator.aiRouter.chat(prompt)
            val reply = if (aiResult.success) aiResult.text
            else "${aiResult.text}".ifBlank { context.getString(R.string.reply_provider_failed) }
            _state.value = _state.value.copy(isThinking = false, lastReply = reply)
            speak(context, reply)
        }
    }

    private suspend fun handleAiCommand(context: Context, text: String): String? {
        val low = text.lowercase().trim().replace("ё", "е")
        return when {
            low.contains("только локальный") || low.contains("только устройство") -> {
                ServiceLocator.settings.setAiMode(AIMode.LOCAL_ONLY)
                "Хорошо, теперь используется только локальный ИИ. Ничего не отправляется в облако."
            }
            low.contains("мой сервер") -> {
                ServiceLocator.settings.setAiMode(AIMode.MY_SERVER)
                "Хорошо, теперь используется ваш сервер."
            }
            low.contains("локальный в приоритете") -> {
                ServiceLocator.settings.setAiMode(AIMode.LOCAL_FIRST)
                "Хорошо, локальный ИИ в приоритете."
            }
            low.contains("внешнего провайдера") || low.contains("openai-compatible") -> {
                ServiceLocator.settings.setAiMode(AIMode.EXTERNAL)
                "Хорошо, используется внешний провайдер."
            }
            low.contains("не отправляй") && low.contains("облако") -> {
                ServiceLocator.settings.setAiMode(AIMode.LOCAL_ONLY)
                "Хорошо, данные не будут покидать устройство."
            }
            low.contains("какой ии") || low.contains("какая модель") || low.contains("кто отвечает") -> {
                ServiceLocator.aiRouter.currentBackendLabel()
            }
            low.contains("какой ии может работать") || low.contains("какие модели") ||
                    low.contains("подходящие варианты") -> {
                offerModels(context)
            }
            else -> null
        }
    }

    /**
     * ТЗ §64: предложить модели, ничего не скачивая.
     */
    private suspend fun offerModels(context: Context): String {
        val report = ServiceLocator.compatibility.bestFit(ServiceLocator.modelRegistry)
            ?: return "Я не нашла модели, совместимой с этим устройством."
        val caps = ServiceLocator.device.current()
        return buildString {
            append("Я нашла локальную модель, совместимую с вашим устройством.\n\n")
            append("Название: ${report.model.name}\n")
            append("Размер: ${report.model.sizeMb} МБ\n")
            append("Требуется памяти: ${report.model.ramRequirementMb} МБ (на устройстве ${caps.ramTotalMb} МБ)\n")
            append("Ожидаемая производительность: ${report.expectedPerf}\n\n")
            append("Скачать можно в разделе «Локальный ИИ». Я ничего не скачиваю без вашего решения.")
        }
    }

    private fun speak(context: Context, text: String) {
        if (ServiceLocator.tts.isAvailable) {
            _state.value = _state.value.copy(isSpeaking = true)
            ServiceLocator.tts.speak(text)
        }
    }

    /**
     * Запустить голосовой ввод.
     */
    fun startListening(context: Context) {
        if (!ServiceLocator.permissionManager.isGranted(android.Manifest.permission.RECORD_AUDIO)) {
            _state.value = _state.value.copy(
                lastReply = "Нет разрешения на микрофон. Его можно выдать в «Разрешениях Светланы»."
            )
            return
        }
        _state.value = _state.value.copy(isListening = true, orbActive = true)
        ServiceLocator.speechRecognizer.startListening(java.util.Locale("ru", "RU"))

        viewModelScope.launch(Dispatchers.Default) {
            var waited = 0
            while (waited < 9000) {
                val result = ServiceLocator.speechRecognizer.result.value
                if (result is com.svetlana.home.voice.SvetlanaSpeechRecognizer.SttResult.Error) {
                    _state.value = _state.value.copy(
                        isListening = false, orbActive = false,
                        lastReply = result.message
                    )
                    return@launch
                }
                if (result is com.svetlana.home.voice.SvetlanaSpeechRecognizer.SttResult.Success) {
                    _state.value = _state.value.copy(isListening = false, orbActive = false)
                    handleInput(context, result.text)
                    return@launch
                }
                kotlinx.coroutines.delay(200)
                waited += 200
            }
            ServiceLocator.speechRecognizer.stopListening()
            _state.value = _state.value.copy(isListening = false, orbActive = false)
        }
    }

    fun stopListening() {
        ServiceLocator.speechRecognizer.stopListening()
        _state.value = _state.value.copy(isListening = false, orbActive = false)
    }

    fun confirmPendingAction(context: Context) {
        val action = _state.value.pendingConfirmation ?: return
        _state.value = _state.value.copy(pendingConfirmation = null)
        viewModelScope.launch(Dispatchers.Default) {
            val result = ServiceLocator.actionRouter.execute(action, confirmed = true)
            _state.value = _state.value.copy(lastReply = result.message)
            speak(context, result.message)
        }
    }

    fun cancelPendingAction() {
        _state.value = _state.value.copy(pendingConfirmation = null, lastReply = "Отменено")
    }

    fun updatePartial(value: String) {
        _state.value = _state.value.copy(partialInput = value)
    }

    override fun onCleared() {
        super.onCleared()
        ServiceLocator.speechRecognizer.stopListening()
    }
}
