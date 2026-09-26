package com.svetlana.home.apps

import kotlinx.serialization.Serializable

/**
 * Приложение, обнаруженное через Android API (PackageManager).
 */
@Serializable
data class AppModel(
    val packageName: String,
    val label: String,
    val aliases: List<String> = emptyList(),
    val isFavorite: Boolean = false,
    val isHidden: Boolean = false,
    val lastUsedAt: Long = 0L,
    val installedAt: Long = 0L,
    val systemApp: Boolean = false,
    val enabled: Boolean = true,
    val versionName: String? = null,
    val category: String = AppCategory.OTHER,
    val control: ControlCapabilities = ControlCapabilities()
)

/**
 * Категории приложений для App Drawer (ТЗ §11).
 * Определяются по пакету и известным Intent-категориям.
 */
object AppCategory {
    const val SYSTEM = "Системные"
    const val SOCIAL = "Общение"
    const val BROWSER = "Браузеры"
    const val TOOLS = "Инструменты"
    const val GAMES = "Игры"
    const val MEDIA = "Медиа"
    const val OTHER = "Другие"

    val ALL = listOf(SYSTEM, SOCIAL, BROWSER, TOOLS, GAMES, MEDIA, OTHER)
}

/**
 * App Capability Matrix: реальные возможности управления конкретным приложением.
 * Показывается пользователю и используется Action Router при выборе способа.
 */
@Serializable
data class ControlCapabilities(
    val canLaunch: Boolean = true,
    val canDeepLink: Boolean = false,
    val canIntent: Boolean = false,
    /**
     * Аудит п.5: Hands-возможности по умолчанию НЕ оптимистичны.
     * canAccessibility/canReadUI/canClick/canInput — это возможности
     * управления через Accessibility-сервис, которые определяются
     * реальной доступностью Hands, а не фактом установки приложения.
     * Раньше они были = true для всех — это ложная capability matrix.
     * См. AppRegistry.build(): они выставляются по реальному состоянию.
     */
    val canAccessibility: Boolean = false,
    val canReadUI: Boolean = false,
    val canClick: Boolean = false,
    val canInput: Boolean = false,
    val canScreenshot: Boolean = false,
    val canVerify: Boolean = false
) {
    /** Способ управления, выбранный по приоритету из ТЗ. */
    fun preferredWay(): String {
        // Нет ни одного реального способа управления — честно показываем «none».
        if (!canLaunch && !canDeepLink && !canIntent && !canAccessibility) return "none"
        return when {
            canIntent -> "intent"
            canDeepLink -> "deeplink"
            canLaunch -> "launch"
            canAccessibility -> "accessibility"
            else -> "none"
        }
    }
}

/**
 * Известные приложениями псевдонимы на русском для голосовых команд.
 * Светлана должна понимать русские названия, даже если приложение зарегистрировано иначе.
 */
object AppAliases {
    val builtIn: Map<String, List<String>> = mapOf(
        "org.telegram.messenger" to listOf("телеграм", "телеграмм", "telegram", "тг"),
        "com.whatsapp" to listOf("ватсап", "вatsapp", "whatsapp", "вацап"),
        "ru.vk.android" to listOf("вк", "вконтакте", "vk", "vkontakte"),
        "com.android.chrome" to listOf("хром", "браузер", "chrome"),
        "com.android.settings" to listOf("настройки", "settings"),
        "com.android.dialer" to listOf("телефон", "звонки", "dialer", "набор номера"),
        "com.android.contacts" to listOf("контакты", "contacts"),
        "com.android.camera" to listOf("камера", "camera"),
        "com.android.gallery" to listOf("галерея", "gallery", "фотографии"),
        "com.android.mms" to listOf("сообщения", "смс", "sms", "mms"),
        "com.google.android.youtube" to listOf("ютуб", "youtube", "you tube"),
        "com.instagram.android" to listOf("инстаграм", "instagram", "инста"),
        "com.facebook.katana" to listOf("фейсбук", "facebook", "фб"),
        "com.spotify.music" to listOf("спотифай", "spotify"),
        "com.yandex.browser" to listOf("яндекс браузер", "yandex"),
        "ru.yandex.yandexmaps" to listOf("яндекс карты", "maps", "карты"),
        "ru.yandex.taxi" to listOf("яндекс такси", "такси", "taxi"),
        "com.mobile.harness" to listOf("mobile harness", "мобайл харнес", "харнес", "harness"),
        "com.svetlana.home" to listOf("светлана", "света", "svetlana", "home")
    )
}
