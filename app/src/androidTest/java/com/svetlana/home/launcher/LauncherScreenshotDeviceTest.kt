package com.svetlana.home.launcher

import android.app.WallpaperManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.os.Build
import android.os.ParcelFileDescriptor
import java.io.ByteArrayOutputStream
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onRoot
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

    private fun shellOut(cmd: String): String {
        val pfd = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(cmd)
        return ParcelFileDescriptor.AutoCloseInputStream(pfd).use { String(it.readBytes()) }
    }

    /**
     * Эмулятор CI иногда показывает ANR-диалог поверх всего. Если виноваты «Настройки» —
     * останавливаем их; если диалог в фокусе — «Назад».
     */
    private fun closeSystemDialogs() {
        for (attempt in 0 until 5) {
            val windows = shellOut("dumpsys window windows")
            val culprit = Regex("Application (?:Not Responding|Error): ([A-Za-z0-9_.]+)")
                .find(windows)?.groupValues?.get(1)
            val focus = windows.lineSequence()
                .filter { it.contains("mCurrentFocus") || it.contains("mFocusedWindow") }
                .joinToString(" ")
            val focused = focus.contains("Not Responding") || focus.contains("Application Error") ||
                focus.contains("isn't responding")
            if (culprit == null && !focused) break
            // Останавливаем только «Настройки» эмулятора: systemui и прочие системные
            // процессы трогать нельзя — это роняет весь прогон.
            if (culprit == "com.android.settings") shell("am force-stop com.android.settings")
            if (focused) shell("input keyevent KEYCODE_BACK")
            Thread.sleep(900)
        }
        shell("am broadcast -a android.intent.action.CLOSE_SYSTEM_DIALOGS")
        shell("input keyevent KEYCODE_WAKEUP")
    }

    private val shotErrors = mutableListOf<String>()

    /** Пишет байты в файл на устройстве от имени shell (каталог /data/local/tmp приложению закрыт). */
    private fun writeDeviceFile(path: String, bytes: ByteArray): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        val fds = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommandRw("dd of=$path")
        ParcelFileDescriptor.AutoCloseOutputStream(fds[1]).use { it.write(bytes) }
        ParcelFileDescriptor.AutoCloseInputStream(fds[0]).use { it.readBytes() }
        return true
    }

    /**
     * Чистый снимок окна launcher: системные окна эмулятора (ANR-диалог «System UI isn't
     * responding») в него не попадают. Окно прозрачное, поэтому под него подкладываются
     * стандартные обои эмулятора (те же, что на обычном снимке).
     */
    private fun cleanShot(name: String): Boolean = try {
        val window = compose.onRoot().captureToImage().asAndroidBitmap()
        val out = Bitmap.createBitmap(window.width, window.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(Color.BLACK)
        val wallpaper = WallpaperManager.getInstance(context).builtInDrawable
        if (wallpaper != null) {
            val w = wallpaper.intrinsicWidth.takeIf { it > 0 } ?: out.width
            val h = wallpaper.intrinsicHeight.takeIf { it > 0 } ?: out.height
            val scale = maxOf(out.width.toFloat() / w, out.height.toFloat() / h)
            val dw = (w * scale).toInt()
            val dh = (h * scale).toInt()
            val left = (out.width - dw) / 2
            val top = (out.height - dh) / 2
            wallpaper.bounds = Rect(left, top, left + dw, top + dh)
            wallpaper.draw(canvas)
        }
        canvas.drawBitmap(window, 0f, 0f, null)
        val png = ByteArrayOutputStream().also { out.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        writeDeviceFile("$DIR/$name.png", png) &&
            (shellOut("stat -c %s $DIR/$name.png").trim().toLongOrNull() ?: 0L) > 1000L
    } catch (t: Throwable) {
        shotErrors += "$name: ${t.javaClass.simpleName}: ${t.message?.take(200)}"
        false
    }

    private fun shot(name: String) {
        compose.waitForIdle()
        closeSystemDialogs()
        Thread.sleep(1200)
        closeSystemDialogs()
        compose.waitForIdle()
        if (!cleanShot(name)) {
            if (shotErrors.none { it.startsWith("$name:") }) shotErrors += "$name: clean shot not written"
            shell("screencap -p $DIR/$name.png")
        }
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
        // Настройки эмулятора часто «висят» после первого запуска и дают ANR-диалог.
        shell("am force-stop com.android.settings")
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
            if (shotErrors.isNotEmpty()) {
                try { writeDeviceFile("$DIR/errors.txt", shotErrors.joinToString("\n").toByteArray()) } catch (t: Throwable) { }
            }
        }
    }

    companion object {
        const val DIR = "/data/local/tmp/svetlana-shots"
    }
}
