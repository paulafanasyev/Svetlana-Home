package com.svetlana.home.core

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.platform.LocalConfiguration

/**
 * Двуязычные тексты интерфейса прямо в коде: русский — основной (и для любых
 * других языков системы, как values/), английский — когда выбран English
 * ([AppLanguage]). Тексты из ресурсов переводятся через values-en/strings.xml.
 */
object Tr {
    fun isEnglish(ctx: Context): Boolean =
        ctx.resources.configuration.locales[0].language == "en"

    fun t(ctx: Context, ru: String, en: String): String = if (isEnglish(ctx)) en else ru

    /** Название режима/статуса из модели данных на языке интерфейса. */
    fun label(ctx: Context, ru: String): String = if (isEnglish(ctx)) labelEn(ru) else ru

    /**
     * Английские названия для фиксированных подписей из enum-ов (AIMode, ModelStatus,
     * ProviderState, HistoryCategory, MemoryMode, ModelCapability). Сами enum-ы остаются
     * русскими (логи, история, тесты), перевод — только при показе. Неизвестная
     * строка возвращается как есть.
     */
    fun labelEn(ru: String): String = LABELS[ru] ?: ru

    private val LABELS = mapOf(
        // AIMode
        "Только устройство" to "Device only",
        "Локальный в приоритете" to "Local first",
        "Автоматический" to "Automatic",
        "Мой сервер" to "My server",
        "Внешний провайдер" to "External provider",
        // ModelStatus
        "Не установлена" to "Not installed",
        "Установлена" to "Installed",
        "Формат проверен" to "Format verified",
        "Runtime готов" to "Runtime ready",
        "Загружена в память" to "Loaded in memory",
        "Inference проверен" to "Inference verified",
        "Vision проверен" to "Vision verified",
        "Проверена на устройстве" to "Verified on device",
        // ProviderState
        "Сохранён" to "Saved",
        "Проверка…" to "Checking…",
        "Ошибка соединения" to "Connection failed",
        "Подключён" to "Connected",
        "Проверен" to "Verified",
        "Используется" to "In use",
        // HistoryCategory
        "Команды" to "Commands",
        "Приложения" to "Apps",
        "Зрение" to "Vision",
        "Модели" to "Models",
        "Сервер" to "Server",
        "Ошибки" to "Errors",
        "Подтверждения" to "Confirmations",
        // MemoryMode
        "Локально" to "Local",
        "Удалённо" to "Remote",
        "Отключено" to "Off",
        // ModelCapability
        "Текст" to "Text",
        "Изображения" to "Images",
        "Аудио" to "Audio",
        "Инструменты" to "Tools"
    )
}

/** Текст на текущем языке интерфейса (Compose). */
@Composable
@ReadOnlyComposable
fun tr(ru: String, en: String): String =
    if (LocalConfiguration.current.locales[0].language == "en") en else ru

/** Название режима/статуса из модели данных на языке интерфейса (Compose). */
@Composable
@ReadOnlyComposable
fun trLabel(ru: String): String =
    if (LocalConfiguration.current.locales[0].language == "en") Tr.labelEn(ru) else ru
