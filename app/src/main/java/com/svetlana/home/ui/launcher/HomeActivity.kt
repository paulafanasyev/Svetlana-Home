package com.svetlana.home.ui.launcher

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
import androidx.lifecycle.viewmodel.compose.viewModel
import com.svetlana.home.core.ServiceLocator
import com.svetlana.home.ui.onboarding.OnboardingFlowContent
import com.svetlana.home.ui.theme.SvetlanaSettingsTheme
import kotlinx.coroutines.flow.first

/**
 * Главный экран SVETLANA HOME — настоящий Android launcher.
 *
 * Выглядит как стандартный рабочий стол: системные обои, часы, сетка
 * иконок, док, строка поиска, свайп вверх — все приложения. Функции
 * Светланы (чат, голос, переводчик, история, настройки) открываются
 * в дополнительных окнах и на панели слева (экран −1).
 */
class HomeActivity : ComponentActivity() {

    /** Счётчик нажатий «Домой», пока launcher уже открыт. */
    private val homePresses = mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        )
        // Рабочий стол рисуется поверх системных обоев пользователя.
        window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER)
        HomeLayoutStore.init(this)
        setContent { SvetlanaSettingsTheme { HomeScreen() } }
    }

    override fun onStart() {
        super.onStart()
        LauncherWidgets.startListening(this)
    }

    override fun onStop() {
        super.onStop()
        LauncherWidgets.stopListening(this)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (Intent.ACTION_MAIN == intent.action) homePresses.intValue += 1
    }

    @Composable
    private fun HomeScreen(viewModel: HomeViewModel = viewModel()) {
        // P0: первый запуск — onboarding (Owner + разрешения), ТЗ §65.
        var showOnboarding by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) {
            val done = ServiceLocator.settings.onboardingDone.first()
            if (!done) showOnboarding = true
        }
        if (showOnboarding) {
            // Onboarding — на непрозрачном фоне, обои под ним не просвечивают.
            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                OnboardingFlowContent(
                    launchHome = { /* Home уже на экране */ },
                    onFinished = { showOnboarding = false }
                )
            }
        } else {
            LauncherScreen(homeViewModel = viewModel, homeSignal = homePresses.intValue)
        }
    }
}
