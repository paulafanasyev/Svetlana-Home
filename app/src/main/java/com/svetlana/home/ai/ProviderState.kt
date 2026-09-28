package com.svetlana.home.ai

/**
 * Состояние внешнего AI-провайдера (аудит §23).
 *
 * Сохранить конфигурацию и активировать провайдер — это **разные** операции.
 * Активация разрешена только после успешного inference.
 */
enum class ProviderState(val label: String) {
    /** Пользователь только сохранил параметры. Inference не проверялся. */
    CONFIGURED("Сохранён"),

    /** Проверяется соединение / модель / inference. */
    CHECKING("Проверка…"),

    /** Endpoint недоступен или ключ невалиден. */
    CONNECTION_FAILED("Ошибка соединения"),

    /** Endpoint + ключ валидны, модель существует. Inference ещё не проверялся. */
    CONNECTED("Подключён"),

    /** Реальный inference прошёл — провайдер можно активировать. */
    VERIFIED("Проверен"),

    /** Провайдер выбран основным. */
    ACTIVE("Используется");

    /**
     * Активировать разрешено только VERIFIED (аудит §23):
     * endpoint valid + credentials valid + model exists + real inference.
     */
    fun canActivate(): Boolean = this == VERIFIED
}

/**
 * Вычисляет состояние провайдера из фактов.
 */
object ProviderStateCalculator {

    fun from(
        configured: Boolean,
        connectionOk: Boolean?,
        modelExists: Boolean?,
        inferenceOk: Boolean?,
        isActive: Boolean
    ): ProviderState = when {
        isActive && inferenceOk == true -> ProviderState.ACTIVE
        inferenceOk == true -> ProviderState.VERIFIED
        connectionOk == false -> ProviderState.CONNECTION_FAILED
        connectionOk == true && modelExists == true -> ProviderState.CONNECTED
        configured -> ProviderState.CONFIGURED
        else -> ProviderState.CONFIGURED
    }
}
