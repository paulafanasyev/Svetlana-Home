package com.svetlana.home.ui.launcher

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.svetlana.home.core.ServiceLocator
import com.svetlana.home.voice.VoiceAgent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Одно сообщение в чате со Светой.
 */
data class ChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val text: String,
    val isUser: Boolean,
    val timestampMs: Long = System.currentTimeMillis(),
    val pending: Boolean = false
)

/**
 * Состояние экрана чата (страница 2).
 *
 * В отличие от голосового экрана, здесь сохраняется вся переписка,
 * а не только последний ответ. Ответы стримятся: текст сообщения
 * обновляется по мере получения от ИИ.
 */
data class ChatUiState(
    val messages: List<ChatMessage> = emptyList(),
    val isThinking: Boolean = false,
    val pendingConfirmation: com.svetlana.home.control.SvetlanaAction? = null
) {
    val inputEnabled: Boolean get() = !isThinking
}

class ChatViewModel : ViewModel() {

    private val _state = MutableStateFlow(ChatUiState())
    val state: StateFlow<ChatUiState> = _state.asStateFlow()

    /**
     * Обработать текст из чата через единое ядро диалога (VoiceAgent).
     * Ответы стримятся прямо в пузырёк сообщения.
     */
    fun handleInput(context: Context, text: String) {
        if (text.isBlank()) return
        val userMsg = ChatMessage(text = text.trim(), isUser = true)
        _state.value = _state.value.copy(
            messages = _state.value.messages + userMsg,
            isThinking = true
        )

        viewModelScope.launch(Dispatchers.Default) {
            // Пузырёк ответа появляется сразу и заполняется по мере стриминга —
            // пользователь видит отклик сразу, не дожидаясь полного ответа.
            val replyId = UUID.randomUUID().toString()
            val outcome = ServiceLocator.voiceAgent.handle(
                text = text,
                speak = true,
                tts = ServiceLocator.tts,
                onPartial = { partial ->
                    val current = _state.value
                    val without = current.messages.filterNot { it.id == replyId }
                    _state.value = current.copy(messages = without + ChatMessage(
                        id = replyId, text = partial, isUser = false, pending = true
                    ))
                }
            )
            when (outcome) {
                is VoiceAgent.Outcome.Replied -> {
                    val without = _state.value.messages.filterNot { it.id == replyId }
                    _state.value = _state.value.copy(
                        isThinking = false,
                        messages = without + ChatMessage(
                            id = replyId, text = outcome.text, isUser = false, pending = false
                        )
                    )
                }
                is VoiceAgent.Outcome.Confirmation -> {
                    val without = _state.value.messages.filterNot { it.id == replyId }
                    _state.value = _state.value.copy(
                        isThinking = false,
                        pendingConfirmation = outcome.action,
                        messages = without + ChatMessage(
                            text = outcome.message, isUser = false
                        )
                    )
                }
            }
        }
    }

    fun confirmPendingAction(context: Context) {
        val action = _state.value.pendingConfirmation ?: return
        _state.value = _state.value.copy(pendingConfirmation = null)
        viewModelScope.launch(Dispatchers.Default) {
            val result = ServiceLocator.actionRouter.execute(action, confirmed = true)
            _state.value = _state.value.copy(
                messages = _state.value.messages + ChatMessage(text = result.message, isUser = false)
            )
            ServiceLocator.tts.speak(result.message)
        }
    }

    fun cancelPendingAction() {
        _state.value = _state.value.copy(
            pendingConfirmation = null,
            messages = _state.value.messages + ChatMessage(text = "Отменено", isUser = false)
        )
    }

    fun clear() {
        _state.value = ChatUiState()
    }

    override fun onCleared() {
        super.onCleared()
        ServiceLocator.tts.stop()
    }
}
