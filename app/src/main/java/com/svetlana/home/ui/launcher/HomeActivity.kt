package com.svetlana.home.ui.launcher

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.svetlana.home.bridge.BridgeController
import com.svetlana.home.core.AppLanguage
import com.svetlana.home.core.ServiceLocator
import com.svetlana.home.permissions.PermissionBootstrap
import com.svetlana.home.ui.onboarding.OnboardingFlowContent
import com.svetlana.home.ui.settings.LanguagePickerFirstRun
import com.svetlana.home.ui.theme.SvetlanaSettingsTheme
import kotlinx.coroutines.flow.first

/**
 * Главный экран SVETLANA HOME — настоящий Android launcher.
 *
 * Выглядит как стандартный рабочий стол: системные обои, часы, сетка
 * иконок, док, строка поиска, свайп вверх — все приложения. Функции
 * Светланы (чат, голос, переводчик, история, настройки) открываются
 * в дополнительных окнах и на панели слева (экран −1). Подключение к ПК
 * (мост к ядру Svetlana 2.0) — в «Настройки → Главный экран».
 *
 * Клавиатура: окно не «сжимается» системой (decorFitsSystemWindows = false), отступ под
 * клавиатуру даёт только Compose (imePadding у полей ввода). Раньше работали оба
 * механизма сразу: если клавиатура закрывалась, пока рабочий стол был в фоне, экран
 * оставался сжатым на 3/4. Теперь при уходе с экрана клавиатура скрывается, а при
 * возврате отступы пересчитываются.
 */
class HomeActivity : ComponentActivity() {

    /** Счётчик нажатий «Домой», пока launcher уже открыт. */
    private val homePresses = mutableIntStateOf(0)

    // Все runtime-разрешения одним системным диалогом, чтобы Светлана
    // сразу работала локально на телефоне. После ответа обновляем уведомление
    // моста (если он включён и POST_NOTIFICATIONS только что выдан).
    private val permissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        BridgeController.startAsync(this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        )
        // Рабочий стол рисуется поверх системных обоев пользователя.
        window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER)
        // Отступы (системные панели и клавиатура) считает только Compose — окно не сжимается.
        WindowCompat.setDecorFitsSystemWindows(window, false)
        HomeLayoutStore.init(this)
        setContent { SvetlanaSettingsTheme { HomeScreen() } }
    }

    override fun onStart() {
        super.onStart()
        LauncherWidgets.startListening(this)
    }

    override fun onResume() {
        super.onResume()
        refreshInsets()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) refreshInsets()
    }

    override fun onStop() {
        super.onStop()
        LauncherWidgets.stopListening(this)
        // Уходим с рабочего стола — убираем клавиатуру и фокус, чтобы вернуться на полный экран.
        hideKeyboard()
    }

    private fun hideKeyboard() {
        try {
            WindowCompat.getInsetsController(window, window.decorView).hide(WindowInsetsCompat.Type.ime())
            currentFocus?.clearFocus()
        } catch (t: Throwable) { /* нет окна — ничего скрывать */ }
    }

    /** Пересчитать отступы: если клавиатура уже закрыта, устаревший отступ пропадёт. */
    private fun refreshInsets() {
        try {
            val decor = window.decorView
            decor.post { ViewCompat.requestApplyInsets(decor) }
        } catch (t: Throwable) { /* ignore */ }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (Intent.ACTION_MAIN == intent.action) homePresses.intValue += 1
    }

    /**
     * Один раз за всё время, после онбординга: все недостающие runtime-разрешения
     * одним диалогом. После отказа не донимаем — дальше Permission Center.
     */
    private fun requestAllPermissionsOnce() {
        if (PermissionBootstrap.isUnderInstrumentation()) return
        val prefs = getSharedPreferences(PermissionBootstrap.PREFS, MODE_PRIVATE)
        if (prefs.getBoolean(PermissionBootstrap.KEY_PROMPTED, false)) return
        val missing = PermissionBootstrap.missing(this)
        if (missing.isEmpty()) return
        prefs.edit().putBoolean(PermissionBootstrap.KEY_PROMPTED, true).apply()
        try {
            permissionsLauncher.launch(missing.toTypedArray())
        } catch (t: Throwable) {
            // Нет системного диалога (редкие OEM) — остаётся мастер разрешений.
        }
    }

    @Composable
    private fun HomeScreen(viewModel: HomeViewModel = viewModel()) {
        // P0: первый запуск — выбор языка, затем onboarding (Owner + разрешения), ТЗ §65.
        // null = ещё читаем состояние: рабочий стол (и диалог разрешений) не
        // создаём, пока точно не знаем, что онбординг пройден.
        var onboardingDone by remember { mutableStateOf<Boolean?>(null) }
        var languageChosen by remember { mutableStateOf(AppLanguage.isChosen(this@HomeActivity)) }
        LaunchedEffect(Unit) {
            onboardingDone = ServiceLocator.settings.onboardingDone.first()
        }
        when (onboardingDone) {
            // Пока читаем настройки — прозрачно, видны обои (без чёрной вспышки).
            null -> Box(Modifier.fillMaxSize())
            // Onboarding — на непрозрачном фоне, обои под ним не просвечивают.
            false -> Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                if (!languageChosen && !PermissionBootstrap.isUnderInstrumentation()) {
                    // Язык — самый первый шаг. При смене языка экран пересоздаётся,
                    // флаг «выбрано» уже сохранён — дальше сразу onboarding.
                    LanguagePickerFirstRun(onDone = { choice ->
                        AppLanguage.set(this@HomeActivity, choice)
                        languageChosen = true
                    })
                } else {
                    OnboardingFlowContent(
                        launchHome = { /* Home уже на экране */ },
                        onFinished = { onboardingDone = true }
                    )
                }
            }
            else -> {
                // Диалог разрешений — только когда онбординг уже позади.
                LaunchedEffect(Unit) { requestAllPermissionsOnce() }
                LauncherScreen(homeViewModel = viewModel, homeSignal = homePresses.intValue)
            }
        }
    }
}
