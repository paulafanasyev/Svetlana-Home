package com.svetlana.home.memory

import kotlinx.serialization.Serializable

/**
 * Категории истории (ТЗ §60).
 */
@Serializable
enum class HistoryCategory(val label: String) {
    COMMANDS("Команды"),
    APPS("Приложения"),
    HANDS("Hands"),
    HARNESS("Mobile Harness"),
    AI("AI"),
    MODELS("Модели"),
    SERVER("Сервер"),
    TRANSLATE("Переводы"),
    ERRORS("Ошибки"),
    CONFIRMATIONS("Подтверждения")
}

@Serializable
data class HistoryEntry(
    val id: String,
    val category: HistoryCategory,
    val title: String,
    val detail: String = "",
    val timestampMs: Long
)

/**
 * Режимы персональной памяти (ТЗ §61).
 */
@Serializable
enum class MemoryMode(val label: String) {
    LOCAL("Локально"),
    REMOTE("Удалённо"),
    DISABLED("Отключено");

    companion object {
        fun fromName(name: String?): MemoryMode? = entries.firstOrNull { it.name == name }
    }
}
