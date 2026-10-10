package com.svetlana.home.launcher

import android.os.ParcelFileDescriptor
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.test.swipeUp
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.svetlana.home.SvetlanaDeviceTest
import com.svetlana.home.core.ServiceLocator
import com.svetlana.home.ui.launcher.HomeActivity
import com.svetlana.home.ui.launcher.HomeItem
import com.svetlana.home.ui.launcher.HomeLayout
import com.svetlana.home.ui.launcher.HomeLayoutStore
import com.svetlana.home.ui.launcher.LauncherModel
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Реальные скриншоты launcher на эмуляторе CI. Каждый шаг проверяет,
 * что нужный экран действительно открылся; снимки кладутся в
 * /data/local/tmp/svetlana-shots и публикуются scripts/run_instrumentation.sh.
 */
@RunWith(AndroidJUnit4::class)
class LauncherScreenshotDeviceTest : SvetlanaDeviceTest() {

    @get:Rule
    val compose = createEmptyComposeRule()

    private fun shell(cmd: String) {
        val pfd = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(cmd)
        ParcelFileDescriptor.AutoCloseInputStream(pfd).use { it.readBytes() }
    }

    private fun exists(tag: String) = compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()

    private fun waitTag(tag: String) = compose.waitUntil(10_000) { exists(tag) }

    private fun waitGone(tag: String) = compose.waitUntil(10_000) { !exists(tag) }

    private fun node(tag: String) = compose.onAllNodesWithTag(tag).onFirst()

    /** Эмулятор CI иногда показывает «System isn't responding» — закрываем системные диалоги перед снимком. */
    private fun closeSystemDialogs() {
        shell("am broadcast -a android.intent.action.CLOSE_SYSTEM_DIALOGS")
        shell("input keyevent KEYCODE_WAKEUP")
    }

    private fun shot(name: String) {
        compose.waitForIdle()
        closeSystemDialogs()
        Thread.sleep(1200)
        closeSystemDialogs()
        compose.waitForIdle()
        shell("screencap -p $DIR/$name.png")
        println("SVETLANA_SCREENSHOT=$name")
    }

    private fun back(scenario: ActivityScenario<HomeActivity>) {
        scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
    }

    @Test
    fun captureLauncherScreens() {
        runBlocking {
            ServiceLocator.settings.setOnboardingDone(true)
            ServiceLocator.settings.setSetupDone(true)
        }
        ServiceLocator.appRegistry.scan()
        val apps = LauncherModel.sortByLabel(
            LauncherModel.launchable(ServiceLocator.appRegistry.apps.value, context.packageName)
        ).map { it.packageName }
        // Раскладка как у реального пользователя: иконки и папка.
        HomeLayoutStore.init(context)
        HomeLayoutStore.update {
            val folder = apps.drop(7).take(4)
            val items = apps.take(7).map { p -> HomeItem.App(p) } +
                (if (folder.size >= 2) listOf(HomeItem.Folder("shots", "Инструменты", folder)) else emptyList())
            HomeLayout(items = items, initialized = true, coachmarkShown = false)
        }
        shell("rm -rf $DIR")
        shell("mkdir -p $DIR")
        // Не показывать диалоги ANR/сбоев системных процессов эмулятора поверх launcher.
        shell("settings put global hide_error_dialogs 1")
        shell("settings put secure anr_show_background 0")
        shell("settings put secure immersive_mode_confirmations confirmed")
        // Даём system_server эмулятора «прогреться», чтобы не ловить ANR-диалог.
        Thread.sleep(15_000)
        closeSystemDialogs()

        val scenario = ActivityScenario.launch(HomeActivity::class.java)
        try {
            waitTag("workspace")
            waitTag("dock")
            shot("01_home")

            node("workspace").performTouchInput { swipeUp() }
            waitTag("app_drawer")
            shot("02_all_apps")
            node("drawer_grid").performTouchInput {
                swipe(Offset(centerX, top + 20f), Offset(centerX, bottom - 20f), 300)
            }
            compose.waitForIdle()
            if (exists("app_drawer")) back(scenario)
            waitGone("app_drawer")

            node("workspace").performTouchInput { longClick(Offset(centerX, centerY)) }
            waitTag("home_menu")
            shot("03_home_menu")
            node("menu_widgets").performClick()
            waitTag("window_widgets")
            shot("04_widgets")
            node("window_close").performClick()
            waitGone("window_widgets")

            node("workspace").performTouchInput { longClick(Offset(centerX, centerY)) }
            waitTag("home_menu")
            node("menu_settings").performClick()
            waitTag("window_home_settings")
            shot("05_home_settings")
            node("window_close").performClick()
            waitGone("window_home_settings")

            if (exists("home_folder")) {
                node("home_folder").performClick()
                waitTag("folder_popup")
                shot("06_folder")
                back(scenario)
                waitGone("folder_popup")
            }

            node("workspace").performTouchInput { swipeRight() }
            waitTag("svetlana_panel")
            shot("07_svetlana_panel")

            node("tile_chat").performClick()
            waitTag("window_chat")
            shot("08_chat_window")
            node("window_close").performClick()
            waitGone("window_chat")

            node("tile_voice").performClick()
            waitTag("window_voice")
            shot("09_voice_window")
            node("window_close").performClick()
            waitGone("window_voice")

            node("svetlana_panel").performTouchInput { swipeLeft() }
            waitTag("search_pill")
            node("search_pill").performClick()
            waitTag("window_search")
            shot("10_search_window")
        } finally {
            scenario.close()
        }
    }

    companion object {
        const val DIR = "/data/local/tmp/svetlana-shots"
    }
}
