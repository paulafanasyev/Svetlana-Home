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

    data class Decision(
        val allowed: Boolean,
        val reason: String,
        val backend: AIBackend
    )

    suspend fun canSend(
        dataType: PrivacyDataType,
        backend: AIBackend,
        requestedMode: AIMode? = null
    ): Decision {
        val mode = requestedMode ?: settings.aiMode.first()
        // Локальный бэкенд — данные не покидают устройство
        if (backend == AIBackend.LOCAL) {
            return Decision(true, "Данные остаются на устройстве", backend)
        }

        // LOCAL_ONLY — полный запрет на передачу наружу
        if (mode == AIMode.LOCAL_ONLY) {
            return Decision(false, "Режим «Только устройство» запрещает передачу данных наружу", backend)
        }

        // Персональная память не уходит внешнему AI автоматически
        if (dataType == PrivacyDataType.HISTORY && backend == AIBackend.EXTERNAL) {
            val memoryMode = settings.memoryMode.first()
            return if (memoryMode == com.svetlana.home.memory.MemoryMode.REMOTE &&
                backend == AIBackend.PERSONAL_SERVER
            ) Decision(true, "Память отправляется на ваш сервер по настройке", backend)
            else Decision(false, "Персональная память не отправляется внешнему AI автоматически", backend)
        }

        // Голос и изображения — только если пользователь разрешил соответствующий режим
        if (dataType in listOf(PrivacyDataType.AUDIO, PrivacyDataType.SCREENSHOT,
                PrivacyDataType.UI_TREE, PrivacyDataType.IMAGE)) {
            return when (mode) {
                AIMode.MY_SERVER -> Decision(true, "Данные отправляются на ваш персональный сервер", AIBackend.PERSONAL_SERVER)
                AIMode.EXTERNAL -> Decision(true, "Данные отправляются выбранному внешнему провайдеру", AIBackend.EXTERNAL)
                AIMode.LOCAL_FIRST, AIMode.AUTO -> Decision(false,
                    "Для этого режима тяжёлые данные остаются на устройстве", AIBackend.LOCAL)
                AIMode.LOCAL_ONLY -> Decision(false, "Только устройство", AIBackend.LOCAL)
            }
        }

        // Текстовые задачи
        return when (mode) {
            AIMode.MY_SERVER -> Decision(true, "Текст идёт на ваш сервер", AIBackend.PERSONAL_SERVER)
            AIMode.EXTERNAL -> Decision(true, "Текст идёт к внешнему провайдеру", AIBackend.EXTERNAL)
            AIMode.LOCAL_FIRST -> Decision(false, "Сначала пробуем локальную модель", AIBackend.LOCAL)
            AIMode.LOCAL_ONLY -> Decision(false, "Только устройство", AIBackend.LOCAL)
            AIMode.AUTO -> Decision(true, "Автоматический выбор маршрута", backend)
        }
    }

    /**
     * Текст локального ответа при блокировке режимом «Только устройство».
     */
    fun localOnlyBlockedMessage(): String =
        "Для этой задачи нужна более мощная модель, но режим «Только устройство» запрещает передачу данных наружу."
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
