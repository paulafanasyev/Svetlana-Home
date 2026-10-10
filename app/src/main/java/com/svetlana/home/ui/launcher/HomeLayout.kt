package com.svetlana.home.ui.launcher

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Элемент рабочего стола: приложение или папка. Порядок = позиция в сетке. */
@Serializable
sealed class HomeItem {
    abstract val key: String

    @Serializable
    @SerialName("app")
    data class App(val pkg: String) : HomeItem() {
        override val key: String get() = "app:$pkg"
    }

    @Serializable
    @SerialName("folder")
    data class Folder(val id: String, val name: String, val apps: List<String>) : HomeItem() {
        override val key: String get() = "folder:$id"
    }
}

/**
 * Сохранённая раскладка рабочего стола (как у Pixel/One UI):
 * порядок иконок и папок, виджеты, служебные флаги.
 * [initialized] = раскладка уже создана — пустой стол остаётся пустым,
 * подсказки больше не подмешиваются.
 */
@Serializable
data class HomeLayout(
    val items: List<HomeItem> = emptyList(),
    val widgets: List<Int> = emptyList(),
    /** Высота виджета в dp, выбранная пользователем (иначе — по minHeight провайдера). */
    val widgetHeights: Map<Int, Int> = emptyMap(),
    val initialized: Boolean = false,
    val coachmarkShown: Boolean = false
) {
    val pinnedPackages: Set<String>
        get() = items.flatMap {
            when (it) {
                is HomeItem.App -> listOf(it.pkg)
                is HomeItem.Folder -> it.apps
            }
        }.toSet()

    val folders: List<HomeItem.Folder> get() = items.filterIsInstance<HomeItem.Folder>()
}

/** Чистые операции над раскладкой — без Android, покрыты unit-тестами. */
object HomeLayoutOps {

    const val FIRST_PAGE_WITH_WIDGETS = 8

    /** Первый запуск: закреплённые раньше приложения, иначе подсказки (кроме дока). */
    fun initial(favorites: List<String>, suggestions: List<String>, dock: Set<String>): HomeLayout {
        val source = favorites.ifEmpty { suggestions }
        val pkgs = source.filter { it !in dock }.distinct()
        return HomeLayout(items = pkgs.map { HomeItem.App(it) }, initialized = true)
    }

    fun pin(layout: HomeLayout, pkg: String): HomeLayout =
        if (pkg in layout.pinnedPackages) layout
        else layout.copy(items = layout.items + HomeItem.App(pkg), initialized = true)

    /** Убрать приложение со стола (и из папок). Папка из одного приложения распадается. */
    fun unpin(layout: HomeLayout, pkg: String): HomeLayout {
        val items = layout.items.mapNotNull { item ->
            when (item) {
                is HomeItem.App -> if (item.pkg == pkg) null else item
                is HomeItem.Folder -> normalizeFolder(item.copy(apps = item.apps - pkg))
            }
        }
        return layout.copy(items = items, initialized = true)
    }

    /** Перенос элемента [from] на позицию [to] (индексы в общем списке). */
    fun move(layout: HomeLayout, from: Int, to: Int): HomeLayout {
        if (from !in layout.items.indices || from == to) return layout
        val list = layout.items.toMutableList()
        val item = list.removeAt(from)
        list.add(to.coerceIn(0, list.size), item)
        return layout.copy(items = list)
    }

    /**
     * Перетаскивание приложения на другое приложение создаёт папку,
     * на папку — добавляет в неё (как в Pixel Launcher).
     */
    fun dropOnto(layout: HomeLayout, from: Int, target: Int, newFolderId: String): HomeLayout {
        if (from == target || from !in layout.items.indices || target !in layout.items.indices) return layout
        val dragged = layout.items[from] as? HomeItem.App ?: return move(layout, from, target)
        val list = layout.items.toMutableList()
        val replacement: HomeItem = when (val t = list[target]) {
            is HomeItem.Folder -> t.copy(apps = (t.apps + dragged.pkg).distinct())
            is HomeItem.App -> HomeItem.Folder(newFolderId, "Папка", listOf(t.pkg, dragged.pkg))
        }
        list[target] = replacement
        list.removeAt(from)
        return layout.copy(items = list)
    }

    /** «В папку…» из меню: [folderId] = null — новая папка на месте приложения. */
    fun addToFolder(layout: HomeLayout, pkg: String, folderId: String?, newFolderId: String): HomeLayout {
        val withoutPkg = unpinKeepingPlace(layout, pkg)
        val base = withoutPkg.first
        val place = withoutPkg.second
        val items = base.items.toMutableList()
        if (folderId == null) {
            val folder = HomeItem.Folder(newFolderId, "Папка", listOf(pkg))
            items.add(place.coerceIn(0, items.size), folder)
        } else {
            val idx = items.indexOfFirst { it is HomeItem.Folder && it.id == folderId }
            if (idx < 0) return layout
            val f = items[idx] as HomeItem.Folder
            items[idx] = f.copy(apps = (f.apps + pkg).distinct())
        }
        return base.copy(items = items, initialized = true)
    }

    fun removeFromFolder(layout: HomeLayout, folderId: String, pkg: String): HomeLayout {
        val idx = layout.items.indexOfFirst { it is HomeItem.Folder && it.id == folderId }
        if (idx < 0) return layout
        val folder = layout.items[idx] as HomeItem.Folder
        val items = layout.items.toMutableList()
        val rest = normalizeFolder(folder.copy(apps = folder.apps - pkg))
        if (rest == null) items.removeAt(idx) else items[idx] = rest
        // Вынутое приложение остаётся на рабочем столе рядом с папкой.
        val insertAt = (if (rest == null) idx else idx + 1).coerceIn(0, items.size)
        if (items.none { it is HomeItem.App && it.pkg == pkg }) items.add(insertAt, HomeItem.App(pkg))
        return layout.copy(items = items)
    }

    fun renameFolder(layout: HomeLayout, folderId: String, name: String): HomeLayout =
        layout.copy(items = layout.items.map {
            if (it is HomeItem.Folder && it.id == folderId) it.copy(name = name.take(30)) else it
        })

    fun addWidget(layout: HomeLayout, id: Int): HomeLayout =
        if (id in layout.widgets) layout else layout.copy(widgets = layout.widgets + id)

    fun removeWidget(layout: HomeLayout, id: Int): HomeLayout =
        layout.copy(widgets = layout.widgets - id, widgetHeights = layout.widgetHeights - id)

    const val WIDGET_MIN_DP = 72
    const val WIDGET_MAX_DP = 360
    const val WIDGET_STEP_DP = 72

    /** Изменить высоту виджета на шаг сетки (72dp), в пределах 72..360dp. */
    fun resizeWidget(layout: HomeLayout, id: Int, currentDp: Int, steps: Int): HomeLayout {
        val next = (currentDp + steps * WIDGET_STEP_DP).coerceIn(WIDGET_MIN_DP, WIDGET_MAX_DP)
        return layout.copy(widgetHeights = layout.widgetHeights + (id to next))
    }

    /** Убрать элемент целиком (папку или приложение) по индексу. */
    fun removeAt(layout: HomeLayout, index: Int): HomeLayout =
        if (index !in layout.items.indices) layout
        else layout.copy(items = layout.items.toMutableList().also { it.removeAt(index) })

    /** Расформировать папку: приложения встают на её место. */
    fun ungroup(layout: HomeLayout, folderId: String): HomeLayout {
        val idx = layout.items.indexOfFirst { it is HomeItem.Folder && it.id == folderId }
        if (idx < 0) return layout
        val folder = layout.items[idx] as HomeItem.Folder
        val items = layout.items.toMutableList()
        items.removeAt(idx)
        items.addAll(idx, folder.apps.map { HomeItem.App(it) })
        return layout.copy(items = items)
    }

    /** Безопасное имя папки: без пробелов по краям, не пустое, не длиннее 30 символов. */
    fun cleanFolderName(name: String): String = name.trim().take(30).ifEmpty { "Папка" }

    /**
     * Индекс вставки при переносе на страницу без конкретной цели:
     * в конец этой страницы ([lastOnPage] — индекс её последнего элемента, null — страница пуста).
     */
    fun endOfPageTarget(from: Int, lastOnPage: Int?, size: Int): Int = when {
        lastOnPage == null -> size
        from < lastOnPage -> lastOnPage
        else -> lastOnPage + 1
    }

    /**
     * Разбивка на страницы рабочего стола. На первой странице меньше места,
     * если там стоят виджеты. Всегда хотя бы одна (возможно пустая) страница.
     */
    fun pages(count: Int, hasWidgets: Boolean, perPage: Int = LauncherModel.MAX_HOME): List<IntRange> {
        val first = if (hasWidgets) FIRST_PAGE_WITH_WIDGETS else perPage
        val result = mutableListOf<IntRange>()
        var start = 0
        var cap = first
        while (start < count) {
            val end = minOf(count, start + cap)
            result += start until end
            start = end
            cap = perPage
        }
        if (result.isEmpty()) result += IntRange.EMPTY
        return result
    }

    /** Видимые элементы вместе с их индексом в сохранённой раскладке (для переноса). */
    fun visibleEntries(items: List<HomeItem>, available: Set<String>): List<IndexedValue<HomeItem>> =
        items.withIndex().mapNotNull { (i, item) -> visibleItem(item, available)?.let { IndexedValue(i, it) } }

    /** Скрыть удалённые/скрытые приложения при отображении (данные не теряем). */
    fun visible(items: List<HomeItem>, available: Set<String>): List<HomeItem> =
        items.mapNotNull { visibleItem(it, available) }

    private fun visibleItem(item: HomeItem, available: Set<String>): HomeItem? = when (item) {
        is HomeItem.App -> if (item.pkg in available) item else null
        is HomeItem.Folder -> {
            val apps = item.apps.filter { p -> p in available }
            if (apps.isEmpty()) null else item.copy(apps = apps)
        }
    }

    private fun normalizeFolder(folder: HomeItem.Folder): HomeItem? = when (folder.apps.size) {
        0 -> null
        1 -> HomeItem.App(folder.apps.first())
        else -> folder
    }

    private fun unpinKeepingPlace(layout: HomeLayout, pkg: String): Pair<HomeLayout, Int> {
        val idx = layout.items.indexOfFirst { it is HomeItem.App && it.pkg == pkg }
        return unpin(layout, pkg) to (if (idx >= 0) idx else layout.items.size)
    }
}
