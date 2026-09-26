package com.svetlana.home.apps

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * ТЗ §11: категории приложений для App Drawer.
 */
class AppCategoryResolverTest {

    @Test
    fun systemApp_isSystemCategory() {
        assertEquals(AppCategory.SYSTEM,
            AppCategoryResolver.resolve("com.android.providers.contacts", isSystem = true, isGame = false))
        assertEquals(AppCategory.SYSTEM,
            AppCategoryResolver.resolve("com.miui.securitycenter", isSystem = true, isGame = false))
    }

    @Test
    fun messengers_areSocial() {
        for (pkg in listOf(
            "org.telegram.messenger", "com.whatsapp", "com.vkontakte.android",
            "com.viber.voip", "com.discord", "org.thoughtcrime.securesms"
        )) {
            assertEquals("Социальная категория для $pkg",
                AppCategory.SOCIAL, AppCategoryResolver.resolve(pkg, isSystem = false, isGame = false))
        }
    }

    @Test
    fun browsers_areBrowserCategory() {
        for (pkg in listOf(
            "com.android.chrome", "org.mozilla.firefox", "com.opera.browser",
            "com.brave.browser", "com.microsoft.emmx"
        )) {
            assertEquals("Категория браузера для $pkg",
                AppCategory.BROWSER, AppCategoryResolver.resolve(pkg, isSystem = false, isGame = false))
        }
    }

    @Test
    fun mediaApps_areMediaCategory() {
        for (pkg in listOf(
            "com.google.android.youtube", "com.spotify.music", "com.netflix.mediaclient",
            "com.miui.gallery", "com.android.camera"
        )) {
            assertEquals("Медиа категория для $pkg",
                AppCategory.MEDIA, AppCategoryResolver.resolve(pkg, isSystem = false, isGame = false))
        }
    }

    @Test
    fun gamesFlag_isGamesCategory() {
        assertEquals(AppCategory.GAMES,
            AppCategoryResolver.resolve("com.example.unknown", isSystem = false, isGame = true))
    }

    @Test
    fun packageNameWithGame_isGamesCategory() {
        assertEquals(AppCategory.GAMES,
            AppCategoryResolver.resolve("com.company.gameapp", isSystem = false, isGame = false))
    }

    @Test
    fun systemFlag_priorityOverGame() {
        // Системное приложение с game-флагом — системное
        assertEquals(AppCategory.SYSTEM,
            AppCategoryResolver.resolve("com.test.something", isSystem = true, isGame = true))
    }

    @Test
    fun unknownApp_isOtherCategory() {
        assertEquals(AppCategory.OTHER,
            AppCategoryResolver.resolve("com.example.randomapp", isSystem = false, isGame = false))
    }

    @Test
    fun categoryCaseInsensitive() {
        // Регистр пакета не влияет на определение
        assertEquals(AppCategory.SOCIAL,
            AppCategoryResolver.resolve("COM.TELEGRAM.MESSENGER", isSystem = false, isGame = false))
    }

    @Test
    fun allCategories_haveDistinctValues() {
        val all = AppCategory.ALL.toSet()
        assertEquals("Категории уникальны", AppCategory.ALL.size, all.size)
        assertEquals("Все категории определены", 7, all.size)
    }
}
