package com.svetlana.home.ai

/**
 * Состояние стрима ответа (аудит §24).
 *
 * Нельзя считать «получено несколько токенов» равным
 * «inference успешно завершён». Поток должен достичь терминального состояния.
 */
enum class StreamState { IDLE, STREAM_STARTED, TOKEN_RECEIVED, STREAM_COMPLETED, STREAM_FAILED }

/**
 * Результат стриминга. Успех — только STREAM_COMPLETED.
 */
data class StreamResult(
    val state: StreamState,
    val text: String,
    val error: String? = null
) {
    val isSuccess: Boolean get() = state == StreamState.STREAM_COMPLETED
    companion object {
        fun completed(text: String) = StreamResult(StreamState.STREAM_COMPLETED, text)
        fun failed(error: String) = StreamResult(StreamState.STREAM_FAILED, "", error)
    }
}
