package com.svetlana.home.ui.launcher

import android.app.Activity
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Send
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.svetlana.home.R
import com.svetlana.home.ui.apps.AppDrawerActivity
import com.svetlana.home.ui.components.GlassCard
import com.svetlana.home.ui.components.LivingOrb
import com.svetlana.home.ui.history.HistoryActivity
import com.svetlana.home.ui.settings.SettingsActivity
import com.svetlana.home.ui.theme.AlmostBlack
import com.svetlana.home.ui.theme.MintPrimary
import com.svetlana.home.ui.theme.MintSoft
import com.svetlana.home.ui.theme.SvetlanaTheme
import com.svetlana.home.ui.theme.TextPrimary
import com.svetlana.home.ui.theme.TextSecondary
import com.svetlana.home.ui.theme.TextTertiary
import com.svetlana.home.ui.translate.TranslateActivity
import kotlinx.coroutines.delay

/**
 * Главный экран SVETLANA HOME.
 *
 * Минималистичный: часы, Living Orb, имя, строка ввода, быстрые действия.
 * Не превращается в перегруженную панель управления (ТЗ §5).
 */
class HomeActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        )
        setContent { SvetlanaTheme { HomeScreen() } }
    }

    @Composable
    private fun HomeScreen(viewModel: HomeViewModel = viewModel()) {
        val context = LocalContext.current
        val uiState by viewModel.state.collectAsState()
        var inputText by remember { mutableStateOf("") }

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

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(AlmostBlack)
        ) {
            // Лёгкий градиентный фон (liquid light)
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                MintPrimary.copy(alpha = 0.05f),
                                AlmostBlack,
                                AlmostBlack
                            )
                        )
                    )
            )

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                // Часы
                Text(
                    text = uiState.clock.ifBlank { "21:42" },
                    style = TextStyle(fontSize = 44.sp, color = TextPrimary, textAlign = TextAlign.Center),
                    modifier = Modifier.padding(top = 24.dp)
                )

                // Орб
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
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
                        modifier = Modifier.padding(horizontal = 32.dp)
                    )
                    if (uiState.aiBackendLabel.isNotBlank()) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = uiState.aiBackendLabel,
                            style = MaterialTheme.typography.labelSmall,
                            color = TextTertiary
                        )
                    }
                    // ТЗ §9: если режим деградирован, Светлана честно
                    // объясняет причину (ресурсы устройства/недоступный renderer).
                    if (uiState.orbReason.isNotBlank()) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = uiState.orbReason,
                            style = MaterialTheme.typography.labelSmall,
                            color = TextTertiary,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(horizontal = 32.dp)
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
                                    color = MintPrimary,
                                    style = MaterialTheme.typography.labelLarge,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(12.dp))
                                        .clickable { viewModel.confirmPendingAction(context) }
                                        .padding(horizontal = 18.dp, vertical = 8.dp)
                                )
                                Spacer(Modifier.width(12.dp))
                                Text(
                                    text = stringResource(R.string.cancel),
                                    color = TextSecondary,
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

                // Поле ввода
                GlassCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp
                    )
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        BasicTextField(
                            value = inputText,
                            onValueChange = { inputText = it; viewModel.updatePartial(it) },
                            textStyle = TextStyle(color = TextPrimary, fontSize = 16.sp),
                            cursorBrush = SolidColor(MintPrimary),
                            keyboardOptions = KeyboardOptions(
                                capitalization = KeyboardCapitalization.Sentences,
                                imeAction = ImeAction.Send
                            ),
                            keyboardActions = KeyboardActions(onSend = {
                                if (inputText.isNotBlank()) {
                                    viewModel.handleInput(context, inputText)
                                    inputText = ""
                                }
                            }),
                            modifier = Modifier.weight(1f),
                            decorationBox = { inner ->
                                if (inputText.isEmpty()) {
                                    Text(
                                        text = stringResource(R.string.home_input_hint),
                                        color = TextTertiary,
                                        style = MaterialTheme.typography.bodyLarge
                                    )
                                }
                                inner()
                            }
                        )
                        Spacer(Modifier.width(8.dp))
                        IconButton(onClick = { viewModel.startListening(context) }) {
                            Icon(
                                imageVector = if (uiState.isListening) Icons.Outlined.GraphicEq else Icons.Outlined.Mic,
                                contentDescription = "Микрофон",
                                tint = if (uiState.isListening) MintPrimary else TextSecondary
                            )
                        }
                        IconButton(onClick = {
                            if (inputText.isNotBlank()) {
                                viewModel.handleInput(context, inputText)
                                inputText = ""
                            }
                        }) {
                            Icon(Icons.Outlined.Send, contentDescription = "Отправить", tint = MintPrimary)
                        }
                    }
                }

                // Быстрые действия
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 16.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    QuickAction(stringResource(R.string.home_apps), Icons.Outlined.Apps) {
                        context.startActivity(Intent(context, AppDrawerActivity::class.java))
                    }
                    QuickAction(stringResource(R.string.home_translator), Icons.Outlined.Translate) {
                        context.startActivity(Intent(context, TranslateActivity::class.java))
                    }
                    QuickAction("История", Icons.Outlined.Settings) {
                        context.startActivity(Intent(context, HistoryActivity::class.java))
                    }
                    QuickAction(stringResource(R.string.home_settings), Icons.Outlined.Settings) {
                        context.startActivity(Intent(context, SettingsActivity::class.java))
                    }
                }
            }
        }
    }

    @Composable
    private fun QuickAction(label: String, icon: ImageVector, onClick: () -> Unit) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .clip(RoundedCornerShape(16.dp))
                .clickable(onClick = onClick)
                .padding(horizontal = 14.dp, vertical = 10.dp)
        ) {
            Icon(icon, contentDescription = label, tint = MintSoft)
            Spacer(Modifier.height(6.dp))
            Text(label, style = MaterialTheme.typography.labelSmall, color = TextSecondary)
        }
    }
}
