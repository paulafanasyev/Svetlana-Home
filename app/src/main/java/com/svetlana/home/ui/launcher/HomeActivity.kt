package com.svetlana.home.ui.launcher

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.runtime.DisposableEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import com.svetlana.home.R
import com.svetlana.home.core.ServiceLocator
import com.svetlana.home.ui.components.GlassCard
import com.svetlana.home.ui.components.LivingOrb
import com.svetlana.home.ui.onboarding.OnboardingFlowContent
import com.svetlana.home.ui.theme.AlmostBlack
import com.svetlana.home.ui.theme.LightBackground
import com.svetlana.home.ui.theme.MintPrimary
import com.svetlana.home.ui.theme.MintSoft
import com.svetlana.home.ui.theme.MintSoftLight
import com.svetlana.home.ui.theme.SvetlanaSettingsTheme
import com.svetlana.home.ui.theme.TextPrimary
import com.svetlana.home.ui.theme.TextSecondary
import com.svetlana.home.ui.theme.TextTertiary
import com.svetlana.home.voice.VoiceAssistantService
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first

/**
 * Главный экран SVETLANA HOME.
 *
 * Три страницы (свайп влево/вправо):
 *  1. Голос — орб и голосовое общение;
 *  2. Чат — текстовая переписка со Светой;
 *  3. Приложения — закреплённые приложения, все приложения и настройки.
 */
class HomeActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        )
        setContent { SvetlanaSettingsTheme { HomeScreen() } }
    }

    @Composable
    private fun HomeScreen(viewModel: HomeViewModel = viewModel()) {
        // P0: первый запуск — показываем onboarding (Owner + разрешения),
        // иначе пользователь никогда не проходит настройку (ТЗ §65).
        var showOnboarding by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) {
            val done = ServiceLocator.settings.onboardingDone.first()
            if (!done) showOnboarding = true
        }
        if (showOnboarding) {
            OnboardingFlowContent(
                launchHome = { /* Home уже на экране — ничего не делаем */ },
                onFinished = { showOnboarding = false }
            )
        } else {
            HomeContent(viewModel)
        }
    }

    @OptIn(ExperimentalFoundationApi::class)
    @Composable
    private fun HomeContent(viewModel: HomeViewModel) {
        val context = LocalContext.current
        val uiState by viewModel.state.collectAsState()

        LaunchedEffect(Unit) {
            while (true) {
                viewModel.refreshClock(context)
                delay(20_000)
            }
        }
        LaunchedEffect(Unit) {
            viewModel.refreshAvatarLevel()
            viewModel.refreshBackendLabel()
        }
        // ТЗ §21: фоновый голосовой ассистент. Цикл прослушивания слова
        // пробуждения живёт в foreground-сервисе, поэтому работает и в фоне.
        // Здесь лишь запускаем сервис (если он ещё не работает) и следим за
        // ответами Светланы для отображения на экране.
        LaunchedEffect(Unit) {
            VoiceAssistantService.startIfEnabled(context)
            viewModel.observeDialogueEvents()
        }

        val pagerState = rememberPagerState(initialPage = 0) { 3 }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
        ) {
            // Лёгкий градиентный фон (liquid light). Берём фактическую тему
            // пользователя через LocalSvetlanaDarkTheme: иначе при
            // «система тёмная, выбрана светлая» фон останется тёмным.
            val isDark = com.svetlana.home.ui.theme.LocalSvetlanaDarkTheme.current
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            colors = if (isDark) listOf(
                                MintPrimary.copy(alpha = 0.05f),
                                AlmostBlack,
                                AlmostBlack
                            ) else listOf(
                                MintSoftLight.copy(alpha = 0.25f),
                                LightBackground,
                                LightBackground
                            )
                        )
                    )
            )

            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize()
            ) { page ->
                when (page) {
                    0 -> VoicePage(viewModel, uiState)
                    1 -> ChatScreen(viewModel = viewModel<ChatViewModel>())
                    2 -> AppsPageScreen(ServiceLocator.appRegistry)
                }
            }

            // Индикатор страниц
            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(bottom = 18.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                repeat(3) { index ->
                    val selected = pagerState.currentPage == index
                    Box(
                        modifier = Modifier
                            .size(if (selected) 10.dp else 7.dp)
                            .clip(CircleShape)
                            .background(
                                if (selected) MintPrimary else MintSoft.copy(alpha = 0.35f)
                            )
                    )
                }
            }
        }
    }

    /**
     * Страница 1: только голосовое общение.
     * Орб, часы, ответ Светланы и кнопка микрофона.
     */
    @Composable
    private fun VoicePage(viewModel: HomeViewModel, uiState: HomeUiState) {
        val context = LocalContext.current

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .statusBarsPadding()
                .padding(bottom = 72.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Часы
            Text(
                text = uiState.clock.ifBlank { "21:42" },
                style = TextStyle(fontSize = 44.sp, color = MaterialTheme.colorScheme.onBackground, textAlign = TextAlign.Center),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 24.dp)
            )

            // Аудит P0: если Светлана ещё не главный экран — показываем
            // подсказку с переходом к системному ROLE_HOME.
            val pm = remember { ServiceLocator.permissionManager }
            // Аудит п.11: состояние должно обновляться при возврате из
            // системных настроек ROLE_HOME, а не кэшироваться на весь
            // жизненный цикл Compose.
            val lifecycleOwner = LocalLifecycleOwner.current
            var isHome by remember { mutableStateOf(pm.isHomeLauncher()) }
            DisposableEffect(lifecycleOwner) {
                val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
                    if (event == Lifecycle.Event.ON_RESUME) {
                        isHome = pm.isHomeLauncher()
                    }
                }
                lifecycleOwner.lifecycle.addObserver(observer)
                onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
            }
            if (!isHome) {
                GlassCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "Светлана ещё не назначена главным экраном",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            text = "Назначить",
                            color = MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .clickable {
                                    val intent = pm.homeRoleIntent()
                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    try { context.startActivity(intent) } catch (t: Throwable) { }
                                }
                                .padding(horizontal = 10.dp, vertical = 6.dp)
                        )
                    }
                }
            }

            // Орб
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(top = 8.dp)
            ) {
                LivingOrb(
                    size = 210.dp,
                    level = uiState.orbLevel,
                    active = uiState.orbActive || uiState.isListening || uiState.isThinking,
                    speaking = uiState.isSpeaking
                )
                Spacer(Modifier.height(18.dp))
                Text(
                    text = stringResource(R.string.svetlana_name),
                    style = MaterialTheme.typography.headlineMedium
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = uiState.lastReply,
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                    maxLines = 6,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 32.dp)
                )
                if (uiState.aiBackendLabel.isNotBlank()) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = uiState.aiBackendLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outlineVariant
                    )
                }
                // ТЗ §9: если режим деградирован, Светлана честно
                // объясняет причину (ресурсы устройства/недоступный renderer).
                if (uiState.orbReason.isNotBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = uiState.orbReason,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outlineVariant,
                        textAlign = TextAlign.Center,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 32.dp)
                    )
                }
            }

            // Подтверждение опасного действия
            AnimatedVisibility(
                visible = uiState.pendingConfirmation != null,
                enter = fadeIn(), exit = fadeOut()
            ) {
                GlassCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp)
                ) {
                    Column {
                        Text(
                            text = uiState.lastReply,
                            style = MaterialTheme.typography.bodyLarge
                        )
                        Spacer(Modifier.height(10.dp))
                        Row {
                            Text(
                                text = stringResource(R.string.confirm),
                                color = MaterialTheme.colorScheme.primary,
                                style = MaterialTheme.typography.labelLarge,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(12.dp))
                                    .clickable { viewModel.confirmPendingAction(context) }
                                    .padding(horizontal = 18.dp, vertical = 8.dp)
                            )
                            Spacer(Modifier.width(12.dp))
                            Text(
                                text = stringResource(R.string.cancel),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.labelLarge,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(12.dp))
                                    .clickable { viewModel.cancelPendingAction() }
                                    .padding(horizontal = 18.dp, vertical = 8.dp)
                            )
                        }
                    }
                }
            }

            // Кнопка голосового ввода
            Box(
                modifier = Modifier
                    .size(72.dp)
                    .clip(CircleShape)
                    .background(MintPrimary.copy(alpha = if (uiState.isListening) 0.3f else 0.16f))
                    .clickable {
                        if (uiState.isListening) viewModel.stopListening()
                        else viewModel.startListening(context)
                    },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (uiState.isListening) Icons.Outlined.GraphicEq else Icons.Outlined.Mic,
                    contentDescription = "Микрофон",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(32.dp)
                )
            }
            Text(
                text = if (uiState.isListening) "Слушаю…" else stringResource(R.string.home_input_hint),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.outlineVariant
            )
        }
    }
}
