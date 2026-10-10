package com.svetlana.home.launcher

import android.os.ParcelFileDescriptor
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.geometry.Offset
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.svetlana.home.SvetlanaDeviceTest
import com.svetlana.home.core.ServiceLocator
import com.svetlana.home.ui.launcher.HomeActivity
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Реальные скриншоты launcher на эмуляторе CI: рабочий стол, все приложения,
 * панель Светланы, чат, голос, поиск и меню рабочего стола.
 * Каждый шаг проверяет, что нужный экран действительно открылся.
 * Снимки кладутся в /data/local/tmp/svetlana-shots, их забирает
 * scripts/run_instrumentation.sh.
 */
@RunWith(AndroidJUnit4::class)
class LauncherScreenshotDeviceTest : SvetlanaDeviceTest() {

    @get:Rule
    val compose = createEmptyComposeRule()

    private fun shell(cmd: String) {
        val pfd = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(cmd)
        ParcelFileDescriptor.AutoCloseInputStream(pfd).use { it.readBytes() }
    }

    private fun waitTag(tag: String) {
        compose.waitUntil(10_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun waitGone(tag: String) {
        compose.waitUntil(10_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isEmpty() }
    }

    private fun shot(name: String) {
        compose.waitForIdle()
        Thread.sleep(900)
        shell("screencap -p $DIR/$name.png")
        println("SVETLANA_SCREENSHOT=$name")
    }

    @Test
    fun captureLauncherScreens() {
        runBlocking {
            ServiceLocator.settings.setOnboardingDone(true)
            ServiceLocator.settings.setSetupDone(true)
        }
        ServiceLocator.appRegistry.scan()
        shell("rm -rf $DIR")
        shell("mkdir -p $DIR")
        // Скрываем системную подсказку «как выйти из полноэкранного режима» и т.п.
        shell("settings put secure immersive_mode_confirmations confirmed")

        val scenario = ActivityScenario.launch(HomeActivity::class.java)
        try {
            waitTag("workspace")
            waitTag("dock")
            shot("01_home")

            compose.onNodeWithTag("workspace").performTouchInput { swipeUp() }
            waitTag("app_drawer")
            shot("02_all_apps")
            compose.onNodeWithTag("drawer_grid").performTouchInput {
                swipe(Offset(centerX, top + 20f), Offset(centerX, bottom - 20f), 300)
            }
            waitGone("app_drawer")

            compose.onNodeWithTag("workspace").performTouchInput { longClick(Offset(centerX, centerY)) }
            waitTag("home_menu")
            shot("03_home_menu")
            compose.onNodeWithTag("home_menu").performClick()
            waitGone("home_menu")

            compose.onNodeWithTag("workspace").performTouchInput { swipeRight() }
            waitTag("svetlana_panel")
            shot("04_svetlana_panel")

            compose.onNodeWithTag("tile_chat").performClick()
            waitTag("window_chat")
            shot("05_chat_window")
            compose.onNodeWithTag("window_close").performClick()
            waitGone("window_chat")

            compose.onNodeWithTag("tile_voice").performClick()
            waitTag("window_voice")
            shot("06_voice_window")
            compose.onNodeWithTag("window_close").performClick()
            waitGone("window_voice")

            compose.onNodeWithTag("svetlana_panel").performTouchInput { swipeLeft() }
            waitTag("search_pill")
            compose.onNodeWithTag("search_pill").performClick()
            waitTag("window_search")
            shot("07_search_window")
        } finally {
            scenario.close()
        }
    }

    companion object {
        const val DIR = "/data/local/tmp/svetlana-shots"
    }
}
