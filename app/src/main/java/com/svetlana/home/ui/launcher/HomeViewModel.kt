package com.svetlana.home.ui.launcher

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.svetlana.home.R
import com.svetlana.home.avatar.AvatarLevel
import com.svetlana.home.core.ServiceLocator
import com.svetlana.home.memory.HistoryCategory
import com.svetlana.home.voice.VoiceAgent
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
     * Наблюдение за событиями диалога из единого ядра (VoiceAgent).
     *
     * Фоновый сервис — единственный исполнитель голосовых команд (чтобы
     * команда не выполнялась дважды). Этот наблюдатель только отображает
     * ответы Светланы на экране, когда приложение открыто.
     *
     * Цепочка: Микрофон → STT → WakeWordMatcher → VoiceAgent → ответ → экран.
     */
    fun observeDialogueEvents() {
        viewModelScope.launch(Dispatchers.Default) {
            ServiceLocator.voiceAgent.events.collect { event ->
                when (event) {
                    is VoiceAgent.DialogueEvent.Reply -> _state.value = _state.value.copy(
                        isThinking = false,
                        isSpeaking = ServiceLocator.tts.isAvailable,
                        lastReply = event.text
                    )
                    is VoiceAgent.DialogueEvent.ConfirmationRequired -> _state.value = _state.value.copy(
                        isThinking = false,
                        lastReply = event.message,
                        pendingConfirmation = event.action
                    )
                }
            }
        }
    }

    fun refreshAvatarLevel() {
        // isReachable() делает сетевой запрос к серверу — только в корутине.
        viewModelScope.launch(Dispatchers.Default) {
            val decision = ServiceLocator.avatarEngine.decide()
            _state.value = _state.value.copy(
                orbLevel = decision.selectedLevel,
                orbReason = if (decision.selectedLevel != decision.requestedLevel)
                    decision.reason else ""
            )
        }
    }

    suspend fun refreshBackendLabel() {
        val label = ServiceLocator.aiRouter.currentBackendLabel()
        _state.value = _state.value.copy(aiBackendLabel = label)
    }

    /**
     * Обработать команду пользователя: текстом или голосом.
     *
     * Делегирует в VoiceAgent — единое ядро диалога, которое используется
     * и главным экраном, и фоновым голосовым сервисом. Поэтому поведение
     * диалога одинаково, где бы пользователь ни обратился к Светлане.
     */
    fun handleInput(context: Context, text: String) {
        if (text.isBlank()) return
        _state.value = _state.value.copy(isThinking = true, lastReply = context.getString(R.string.home_thinking))

        viewModelScope.launch(Dispatchers.Default) {
            val outcome = ServiceLocator.voiceAgent.handle(
                text = text,
                speak = true,
                tts = ServiceLocator.tts,
                onPartial = { partial ->
                    // Ответ ИИ стримится: пользователь сразу видит текст,
                    // не дожидаясь полного ответа.
                    _state.value = _state.value.copy(lastReply = partial)
                }
            )
            when (outcome) {
                is VoiceAgent.Outcome.Replied -> _state.value = _state.value.copy(
                    isThinking = false,
                    lastReply = outcome.text
                )
                is VoiceAgent.Outcome.Confirmation -> _state.value = _state.value.copy(
                    isThinking = false,
                    lastReply = outcome.message,
                    pendingConfirmation = outcome.action
                )
            }
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
        ServiceLocator.speechRecognizer.startListening(java.util.Locale.forLanguageTag("ru-RU"))

        viewModelScope.launch(Dispatchers.Default) {
            try {
                val result = ServiceLocator.speechRecognizer.awaitResult(9_000L)
                when (result) {
                    is com.svetlana.home.voice.SvetlanaSpeechRecognizer.SttResult.Error -> {
                        _state.value = _state.value.copy(
                            isListening = false, orbActive = false,
                            lastReply = result.message
                        )
                    }
                    is com.svetlana.home.voice.SvetlanaSpeechRecognizer.SttResult.Success -> {
                        _state.value = _state.value.copy(isListening = false, orbActive = false)
                        handleInput(context, result.text)
                    }
                    null -> {
                        ServiceLocator.speechRecognizer.stopListening()
                        _state.value = _state.value.copy(
                            isListening = false, orbActive = false,
                            lastReply = "Не удалось дождаться результата распознавания"
                        )
                    }
                }
            } finally {
                ServiceLocator.speechRecognizer.finishSession()
            }
        }
    }

    fun stopListening() {
        // Явное касание орба = отмена ручной сессии. Полностью освобождаем
        // STT и возвращаем state machine в IDLE, чтобы следующий запуск работал.
        ServiceLocator.speechRecognizer.finishSession()
        _state.value = _state.value.copy(isListening = false, orbActive = false)
    }

    fun confirmPendingAction(context: Context) {
        val action = _state.value.pendingConfirmation ?: return
        _state.value = _state.value.copy(pendingConfirmation = null)
        viewModelScope.launch(Dispatchers.Default) {
            val result = ServiceLocator.actionRouter.execute(action, confirmed = true)
            _state.value = _state.value.copy(lastReply = result.message)
            ServiceLocator.tts.speak(result.message)
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
