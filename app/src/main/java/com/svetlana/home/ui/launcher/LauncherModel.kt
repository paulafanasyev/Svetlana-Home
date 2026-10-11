package com.svetlana.home.ui.launcher

import com.svetlana.home.apps.AppModel
import java.text.Collator
import java.util.Locale

/** Слоты дока по умолчанию — как в стандартном Android launcher. */
enum class DockRole { PHONE, MESSAGES, BROWSER, CAMERA }

/** Сетка главного экрана: закреплённые пользователем или подсказанные приложения. */
data class HomeGrid(val apps: List<AppModel>, val suggested: Boolean)

/**
 * Чистая логика раскладки launcher (без Android API), чтобы её можно было
 * проверить обычными unit-тестами: док, сетка, поиск и сортировка Drawer.
 */
object LauncherModel {
    const val HOME_COLUMNS = 4
    const val HOME_ROWS = 4
    const val DOCK_SIZE = 4
    const val MAX_HOME = HOME_COLUMNS * HOME_ROWS

    /** Популярные приложения для пустого главного экрана (первый запуск). */
    val SUGGESTED_PACKAGES = listOf(
        "org.telegram.messenger", "com.whatsapp", "com.vkontakte.android", "ru.vk.android",
        "com.google.android.youtube", "ru.yandex.yandexmaps", "com.google.android.apps.maps",
        "com.google.android.apps.photos", "com.google.android.gm", "com.android.vending",
        "com.google.android.calendar", "com.android.calendar", "com.google.android.deskclock",
        "com.android.deskclock", "com.android.contacts", "com.google.android.contacts",
        "com.android.gallery3d", "com.miui.gallery", "com.android.settings"
    )

    private val collator: Collator = Collator.getInstance(Locale("ru", "RU")).apply {
        strength = Collator.PRIMARY
    }

    /** Приложения, которые launcher показывает: с иконкой, включённые, не скрытые, кроме самой Светланы. */
    fun launchable(apps: List<AppModel>, selfPackage: String): List<AppModel> =
        apps.filter {
            it.isLaunchable && !it.isHidden && it.enabled &&
                it.packageName != selfPackage && !it.packageName.startsWith("com.svetlana.home")
        }.distinctBy { it.packageName }

    /**
     * Док: телефон, сообщения, браузер, камера (по приложениям по умолчанию),
     * недостающие слоты заполняются недавними и популярными приложениями.
     */
    fun dock(apps: List<AppModel>, roles: Map<DockRole, String?>): List<AppModel> {
        val byPkg = apps.associateBy { it.packageName }
        val result = mutableListOf<AppModel>()
        for (role in DockRole.values()) {
            val app = roles[role]?.let { byPkg[it] } ?: continue
            if (result.none { it.packageName == app.packageName }) result += app
        }
        if (result.size < DOCK_SIZE) {
            val extra = ranked(apps).filter { a -> result.none { it.packageName == a.packageName } }
            result += extra.take(DOCK_SIZE - result.size)
        }
        return result.take(DOCK_SIZE)
    }

    /**
     * Сетка главного экрана. Если пользователь ещё ничего не закрепил,
     * показываем подсказки (недавние + популярные), как делает Pixel Launcher
     * при первом запуске, и честно помечаем их как suggested.
     */
    fun homeGrid(apps: List<AppModel>, dock: List<AppModel>): HomeGrid {
        val dockPkgs = dock.map { it.packageName }.toSet()
        val favorites = apps.filter { it.isFavorite && it.packageName !in dockPkgs }
        if (favorites.isNotEmpty()) return HomeGrid(favorites.take(MAX_HOME), suggested = false)
        val suggested = ranked(apps).filter { it.packageName !in dockPkgs }.take(HOME_COLUMNS * 2)
        return HomeGrid(suggested, suggested = true)
    }

    /** Недавние → популярные → по алфавиту. */
    fun ranked(apps: List<AppModel>): List<AppModel> {
        val recent = apps.filter { it.lastUsedAt > 0 }.sortedByDescending { it.lastUsedAt }
        val popular = SUGGESTED_PACKAGES.mapNotNull { p -> apps.firstOrNull { it.packageName == p } }
        val rest = sortByLabel(apps)
        return (recent + popular + rest).distinctBy { it.packageName }
    }

    fun recent(apps: List<AppModel>, limit: Int = HOME_COLUMNS): List<AppModel> =
        apps.filter { it.lastUsedAt > 0 }.sortedByDescending { it.lastUsedAt }.take(limit)

    fun sortByLabel(apps: List<AppModel>): List<AppModel> =
        apps.sortedWith { a, b -> collator.compare(a.label.trim(), b.label.trim()) }

    /** Поиск в Drawer: сначала совпадение с начала названия, затем по слову, затем по псевдониму/пакету. */
    fun search(apps: List<AppModel>, query: String): List<AppModel> {
        val q = query.trim().lowercase(Locale.ROOT)
        if (q.isEmpty()) return sortByLabel(apps)
        fun score(a: AppModel): Int {
            val label = a.label.lowercase(Locale.ROOT)
            return when {
                label.startsWith(q) -> 0
                label.split(' ', '-', '.').any { it.startsWith(q) } -> 1
                label.contains(q) -> 2
                a.aliases.any { it.lowercase(Locale.ROOT).contains(q) } -> 3
                a.packageName.lowercase(Locale.ROOT).contains(q) -> 4
                else -> -1
            }
        }
        return apps.map { it to score(it) }
            .filter { it.second >= 0 }
            .sortedWith(compareBy<Pair<AppModel, Int>> { it.second }
                .thenComparator { x, y -> collator.compare(x.first.label, y.first.label) })
            .map { it.first }
    }

    /** Первая буква для быстрого индекса Drawer: буква или «#». */
    fun indexLetter(label: String): String {
        val c = label.trim().firstOrNull() ?: return "#"
        return if (c.isLetter()) c.uppercaseChar().toString() else "#"
    }

    /** Пакеты, которые станут закреплёнными после «На главный экран». */
    fun pinnedAfterPin(grid: HomeGrid, pkg: String): List<String> {
        val base = grid.apps.map { it.packageName }
        return if (grid.suggested) (base + pkg).distinct() else listOf(pkg)
    }

    /** Пакеты, которые останутся закреплёнными после «Убрать с главного экрана». */
    fun pinnedAfterUnpin(grid: HomeGrid, pkg: String): List<String> =
        if (grid.suggested) grid.apps.map { it.packageName }.filter { it != pkg } else emptyList()
}
