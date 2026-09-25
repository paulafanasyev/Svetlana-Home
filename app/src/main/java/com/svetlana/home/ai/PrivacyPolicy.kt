package com.svetlana.home.ai

import com.svetlana.home.memory.MemoryMode

/**
 * PrivacyPolicy — чистая функция privacy-решений (ТЗ §49).
 *
 * Не зависит от Android / DataStore, поэтому её логику можно
 * протестировать unit-тестами без устройства. Используется и
 * PrivacyRouter'ом, и HybridPipeline — единственный источник правды
 * для решения «можно ли отправить эти данные этому бэкенду».
 *
 * LOCAL_ONLY физически исключает любую передачу наружу (ТЗ §50).
 */
object PrivacyPolicy {

    data class Decision(
        val allowed: Boolean,
        val reason: String,
        val backend: AIBackend
    )

    fun decide(
        dataType: PrivacyDataType,
        backend: AIBackend,
        mode: AIMode,
        memoryMode: MemoryMode = MemoryMode.LOCAL
    ): Decision {
        // Локальный бэкенд — данные не покидают устройство
        if (backend == AIBackend.LOCAL) {
            return Decision(true, "Данные остаются на устройстве", backend)
        }

        // LOCAL_ONLY — полный запрет на передачу наружу
        if (mode == AIMode.LOCAL_ONLY) {
            return Decision(false, "Режим «Только устройство» запрещает передачу данных наружу", backend)
        }

        // Персональная память не уходит внешнему AI автоматически (ТЗ §61)
        if (dataType == PrivacyDataType.HISTORY && backend == AIBackend.EXTERNAL) {
            return if (memoryMode == MemoryMode.REMOTE && backend == AIBackend.PERSONAL_SERVER) {
                Decision(true, "Память отправляется на ваш сервер по настройке", backend)
            } else {
                Decision(false, "Персональная память не отправляется внешнему AI автоматически", backend)
            }
        }

        // Голос и изображения — только если пользователь разрешил
        if (dataType in HEAVY_DATA) {
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

    private val HEAVY_DATA = listOf(
        PrivacyDataType.AUDIO,
        PrivacyDataType.SCREENSHOT,
        PrivacyDataType.UI_TREE,
        PrivacyDataType.IMAGE
    )

    /**
     * Текст локального ответа при блокировке режимом «Только устройство».
     */
    fun localOnlyBlockedMessage(): String =
        "Для этой задачи нужна более мощная модель, но режим «Только устройство» запрещает передачу данных наружу."
}
