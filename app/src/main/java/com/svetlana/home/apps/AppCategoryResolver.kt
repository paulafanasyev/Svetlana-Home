package com.svetlana.home.apps

/**
 * AppCategoryResolver — определение категории приложения по пакету и
 * системным флагам (ТЗ §11).
 *
 * Чистая функция без Android-зависимостей: используется и в AppRegistry,
 * и в unit-тестах. ApplicationInfo передаётся как данные, а не как
 * Android-класс, чтобы логика покрывалась на JVM.
 */
object AppCategoryResolver {

    data class AppInfo(
        val packageName: String,
        val isSystem: Boolean = false,
        val isGame: Boolean = false
    )

    private val SOCIAL = listOf(
        "tele", "gram", "whats", "vk", "viber", "discord", "signal",
        "messenger", "wechat", "telegram", "vkontakte", "securesms"
    )
    private val BROWSER = listOf(
        "browser", "chrome", "firefox", "opera", "brave", "edge", "emmx"
    )
    private val MEDIA = listOf(
        "video", "player", "music", "media", "gallery", "photo", "camera",
        "spotify", "youtube", "netflix"
    )
    private val TOOLS = listOf(
        "calculator", "calendar", "clock", "notes", "files", "file", "manager",
        "security", "antivirus", "cleaner", "settings", "tools", "editor"
    )

    fun resolve(info: AppInfo): String {
        val pkg = info.packageName.lowercase()
        if (info.isSystem) return AppCategory.SYSTEM
        if (info.isGame || pkg.contains("game")) return AppCategory.GAMES
        if (SOCIAL.any { pkg.contains(it) }) return AppCategory.SOCIAL
        if (BROWSER.any { pkg.contains(it) }) return AppCategory.BROWSER
        if (MEDIA.any { pkg.contains(it) }) return AppCategory.MEDIA
        if (TOOLS.any { pkg.contains(it) }) return AppCategory.TOOLS
        return AppCategory.OTHER
    }

    fun resolve(packageName: String, isSystem: Boolean, isGame: Boolean): String =
        resolve(AppInfo(packageName, isSystem, isGame))
}
