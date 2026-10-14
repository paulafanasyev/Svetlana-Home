package com.svetlana.home.ui.launcher

import android.app.role.RoleManager
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.platform.LocalContext
import androidx.core.view.WindowCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.svetlana.home.core.AssistLaunch
import com.svetlana.home.ui.theme.SvetlanaSettingsTheme

/**
 * Светлана как цифровой помощник по умолчанию (роль ASSISTANT через ACTION_ASSIST).
 *
 * Долгое нажатие «Домой» / кнопки питания / свайп из угла открывает окно голоса и
 * сразу слушает — из любого приложения, не выходя на рабочий стол. Команды идут через тот
 * же VoiceAgent и ActionRouter (с теми же подтверждениями), что и на главном экране.
 *
 * Микрофон включается сам только если Светлана выбрана помощником ([AssistLaunch]).
 * Отдельная задача (taskAffinity) и excludeFromRecents: окно не смешивается с рабочим столом
 * и не остаётся в недавних.
 */
class AssistActivity : ComponentActivity() {

    /** Счётчик вызовов ассистента: каждый новый вызов заново включает микрофон. */
    private val listenRequests = mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        if (savedInstanceState == null) requestListening(intent)
        setContent { SvetlanaSettingsTheme { AssistScreen(listenRequests.intValue, onClose = ::finish) } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        requestListening(intent)
    }

    private fun requestListening(intent: Intent?) {
        if (AssistLaunch.shouldListen(intent?.action, holdsAssistantRole())) listenRequests.intValue += 1
    }

    private fun holdsAssistantRole(): Boolean = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            getSystemService(RoleManager::class.java)?.isRoleHeld(RoleManager.ROLE_ASSISTANT) == true
        } else {
            AssistLaunch.isAssistantSetting(Settings.Secure.getString(contentResolver, "assistant"), packageName)
        }
    } catch (t: Throwable) {
        false
    }
}

@Composable
private fun AssistScreen(listenRequests: Int, onClose: () -> Unit) {
    val context = LocalContext.current
    val viewModel: HomeViewModel = viewModel()
    val uiState by viewModel.state.collectAsState()
    LaunchedEffect(Unit) {
        viewModel.refreshClock(context)
        viewModel.observeDialogueEvents()
        viewModel.refreshAvatarLevel()
        viewModel.refreshBackendLabel()
    }
    LaunchedEffect(listenRequests) {
        if (listenRequests > 0 && !viewModel.state.value.isListening) viewModel.startListening(context)
    }
    SvetlanaWindowFrame(tag = "window_assist", onClose = {
        viewModel.stopListening()
        onClose()
    }) {
        VoiceWindowContent(viewModel, uiState)
    }
}
