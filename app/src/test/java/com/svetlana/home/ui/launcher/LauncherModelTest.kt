package com.svetlana.home.ui.launcher

import com.google.common.truth.Truth.assertThat
import com.svetlana.home.apps.AppModel
import org.junit.Test

class LauncherModelTest {

    private fun app(
        pkg: String,
        label: String,
        fav: Boolean = false,
        used: Long = 0L,
        launchable: Boolean = true,
        hidden: Boolean = false,
        aliases: List<String> = emptyList()
    ) = AppModel(
        packageName = pkg, label = label, isFavorite = fav, lastUsedAt = used,
        isLaunchable = launchable, isHidden = hidden, aliases = aliases
    )

    private val phone = app("com.android.dialer", "Телефон")
    private val sms = app("com.android.messaging", "Сообщения")
    private val chrome = app("com.android.chrome", "Chrome")
    private val camera = app("com.android.camera2", "Камера")
    private val tg = app("org.telegram.messenger", "Telegram", aliases = listOf("телеграм"))
    private val vk = app("com.vkontakte.android", "ВКонтакте")
    private val settings = app("com.android.settings", "Настройки")

    @Test
    fun launchableHidesSelfHiddenAndNonLaunchable() {
        val all = listOf(
            phone, app("com.svetlana.home.debug", "Svetlana Home"),
            app("x.hidden", "Hidden", hidden = true), app("x.service", "Service", launchable = false)
        )
        val result = LauncherModel.launchable(all, "com.svetlana.home.debug")
        assertThat(result.map { it.packageName }).containsExactly("com.android.dialer")
    }

    @Test
    fun dockUsesDefaultAppsInStandardOrder() {
        val apps = listOf(tg, chrome, camera, sms, phone)
        val dock = LauncherModel.dock(
            apps,
            mapOf(
                DockRole.PHONE to phone.packageName, DockRole.MESSAGES to sms.packageName,
                DockRole.BROWSER to chrome.packageName, DockRole.CAMERA to camera.packageName
            )
        )
        assertThat(dock.map { it.label }).containsExactly("Телефон", "Сообщения", "Chrome", "Камера").inOrder()
    }

    @Test
    fun dockFillsMissingRolesWithoutDuplicates() {
        val apps = listOf(phone, tg, vk, settings)
        val dock = LauncherModel.dock(apps, mapOf(DockRole.PHONE to phone.packageName, DockRole.BROWSER to "not.installed"))
        assertThat(dock).hasSize(LauncherModel.DOCK_SIZE)
        assertThat(dock.first()).isEqualTo(phone)
        assertThat(dock.map { it.packageName }.toSet()).hasSize(dock.size)
    }

    @Test
    fun homeGridShowsSuggestionsUntilUserPins() {
        val apps = listOf(phone, tg, vk, settings)
        val grid = LauncherModel.homeGrid(apps, dock = listOf(phone))
        assertThat(grid.suggested).isTrue()
        assertThat(grid.apps).doesNotContain(phone)
        assertThat(grid.apps.first()).isEqualTo(tg)

        val pinned = LauncherModel.homeGrid(listOf(phone, tg, vk.copy(isFavorite = true)), dock = listOf(phone))
        assertThat(pinned.suggested).isFalse()
        assertThat(pinned.apps).containsExactly(vk.copy(isFavorite = true))
    }

    @Test
    fun pinningFromSuggestionsKeepsCurrentHomeIcons() {
        val grid = HomeGrid(listOf(tg, vk), suggested = true)
        assertThat(LauncherModel.pinnedAfterPin(grid, settings.packageName))
            .containsExactly(tg.packageName, vk.packageName, settings.packageName).inOrder()
        assertThat(LauncherModel.pinnedAfterUnpin(grid, tg.packageName)).containsExactly(vk.packageName)
        val real = HomeGrid(listOf(tg), suggested = false)
        assertThat(LauncherModel.pinnedAfterPin(real, vk.packageName)).containsExactly(vk.packageName)
    }

    @Test
    fun recentAppsComeFirstInRanking() {
        val ranked = LauncherModel.ranked(listOf(settings, vk.copy(lastUsedAt = 5), tg.copy(lastUsedAt = 9)))
        assertThat(ranked.map { it.packageName }.take(2))
            .containsExactly(tg.packageName, vk.packageName).inOrder()
    }

    @Test
    fun searchRanksPrefixThenWordThenAlias() {
        val apps = listOf(app("a.b", "Мои Настройки"), settings, tg, vk)
        assertThat(LauncherModel.search(apps, "нас").map { it.label })
            .containsExactly("Настройки", "Мои Настройки").inOrder()
        assertThat(LauncherModel.search(apps, "телеграм")).containsExactly(tg)
        assertThat(LauncherModel.search(apps, "zzz")).isEmpty()
        assertThat(LauncherModel.search(apps, " ")).hasSize(apps.size)
    }

    @Test
    fun drawerSortIsCaseInsensitiveRussianCollation() {
        val sorted = LauncherModel.sortByLabel(listOf(app("1", "яндекс"), app("2", "Алиса"), app("3", "ёлка"), app("4", "Егерь")))
        assertThat(sorted.map { it.label }).containsExactly("Алиса", "Егерь", "ёлка", "яндекс").inOrder()
    }

    @Test
    fun indexLetter() {
        assertThat(LauncherModel.indexLetter("telegram")).isEqualTo("T")
        assertThat(LauncherModel.indexLetter("2ГИС")).isEqualTo("#")
        assertThat(LauncherModel.indexLetter("")).isEqualTo("#")
    }
}
