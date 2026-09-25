package com.svetlana.home.ai

import com.svetlana.home.store.SettingsRepository
import kotlinx.coroutines.flow.first

/**
 * PrivacyRouter — решает, можно ли передавать конкретный тип данных
 * конкретному бэкенду (ТЗ §49).
 *
 * Правила:
 * - LOCAL_ONLY запрещает любую передачу наружу (ТЗ §50);
 * - персональная память не отправляется внешнему AI автоматически (ТЗ §61);
 * - скриншоты и UI tree можно отправлять только на выбранный пользователем сервер
 *   или провайдер (и только если это разрешено).
 */
class PrivacyRouter(private val settings: SettingsRepository) {

    suspend fun canSend(
        dataType: PrivacyDataType,
        backend: AIBackend,
        requestedMode: AIMode? = null
    ): PrivacyPolicy.Decision {
        // Делегируем в чистую функцию — единый источник privacy-логики,
        // который покрывается unit-тестами без устройства.
        val mode = requestedMode ?: settings.aiMode.first()
        return if (dataType == PrivacyDataType.HISTORY) {
            PrivacyPolicy.decide(dataType, backend, mode, settings.memoryMode.first())
        } else {
            PrivacyPolicy.decide(dataType, backend, mode)
        }
    }

    /**
     * Текст локального ответа при блокировке режимом «Только устройство».
     */
    fun localOnlyBlockedMessage(): String = PrivacyPolicy.localOnlyBlockedMessage()
}

/**
 * ModelRouter — определяет LOCAL / REMOTE / HYBRID (ТЗ §47)
 * с учётом сложности задачи, выбора провайдера, приватности, батареи, сети, thermal.
 */
class ModelRouter(
    private val device: com.svetlana.home.device.DeviceCapabilityManager,
    private val settings: SettingsRepository,
    private val serverManager: com.svetlana.home.server.PersonalServerManager,
    private val privacyRouter: PrivacyRouter
) {

    data class Routing(
        val decision: RouteDecision,
        val backend: AIBackend,
        val reason: String
    )

    suspend fun route(
        complexity: TaskComplexity,
        dataType: PrivacyDataType = PrivacyDataType.TEXT
    ): Routing {
        val mode = settings.aiMode.first()
        val caps = device.current()

        when (mode) {
            AIMode.LOCAL_ONLY -> return Routing(RouteDecision.LOCAL, AIBackend.LOCAL, "Режим: только устройство")
            AIMode.MY_SERVER -> {
                if (!caps.networkAvailable) return Routing(RouteDecision.LOCAL, AIBackend.LOCAL,
                    "Сервер недоступен: нет сети. Остаюсь на устройстве")
                return Routing(RouteDecision.REMOTE, AIBackend.PERSONAL_SERVER, "Режим: мой сервер")
            }
            AIMode.EXTERNAL -> {
                if (!caps.networkAvailable) return Routing(RouteDecision.LOCAL, AIBackend.LOCAL,
                    "Внешний провайдер недоступен: нет сети")
                return Routing(RouteDecision.REMOTE, AIBackend.EXTERNAL, "Режим: внешний провайдер")
            }
            AIMode.LOCAL_FIRST -> {
                if (complexity == TaskComplexity.HEAVY && caps.networkAvailable) {
                    val privacy = privacyRouter.canSend(dataType, AIBackend.PERSONAL_SERVER, mode)
                    if (privacy.allowed && serverManager.isReachable()) {
                        return Routing(RouteDecision.HYBRID, AIBackend.HYBRID,
                            "Локальная предобработка, тяжёлый вывод на сервере")
                    }
                }
                return Routing(RouteDecision.LOCAL, AIBackend.LOCAL, "Режим: локальный в приоритете")
            }
            AIMode.AUTO -> {
                // Адаптивный выбор
                val heavy = complexity == TaskComplexity.HEAVY
                val lowResources = caps.ramAvailableMb < 1500 ||
                        caps.thermalStatus in listOf("moderate", "severe", "critical") ||
                        caps.batteryPercent in 0..12
                if (heavy && caps.networkAvailable && serverManager.isReachable() && !lowResources) {
                    return Routing(RouteDecision.HYBRID, AIBackend.HYBRID, "Авто: гибрид local+server")
                }
                if (heavy && !caps.networkAvailable) {
                    return Routing(RouteDecision.LOCAL, AIBackend.LOCAL,
                        "Авто: нет сети, тяжёлая задача остаётся на устройстве")
                }
                return Routing(RouteDecision.LOCAL, AIBackend.LOCAL, "Авто: устройство справится")
            }
        }
    }

    enum class TaskComplexity { LIGHT, MEDIUM, HEAVY }
}
