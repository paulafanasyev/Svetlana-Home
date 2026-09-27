package com.svetlana.home.ui.launcher

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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Send
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.svetlana.home.R
import com.svetlana.home.ui.components.GlassCard
import com.svetlana.home.ui.theme.AlmostBlack
import com.svetlana.home.ui.theme.MintPrimary
import com.svetlana.home.ui.theme.MintSoft
import com.svetlana.home.ui.theme.TextPrimary
import com.svetlana.home.ui.theme.TextSecondary
import com.svetlana.home.ui.theme.TextTertiary

/**
 * Страница 2: чат со Светой.
 *
 * Отличается от голосового экрана: сохраняется вся переписка,
 * ответ можно перечитать, прокрутив историю.
 */
@Composable
fun ChatScreen(viewModel: ChatViewModel) {
    val context = LocalContext.current
    val uiState by viewModel.state.collectAsState()
    var inputText by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    // Автоскролл к последнему сообщению
    LaunchedEffect(uiState.messages.size) {
        if (uiState.messages.isNotEmpty()) {
            listState.animateScrollToItem(uiState.messages.lastIndex)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(AlmostBlack)
            .imePadding()
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        Text(
            text = stringResource(R.string.svetlana_name),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, top = 12.dp, bottom = 4.dp)
        )
        Text(
            text = "Чат",
            style = MaterialTheme.typography.labelMedium,
            color = TextTertiary,
            modifier = Modifier.padding(start = 20.dp, bottom = 8.dp)
        )

        // Лента сообщений
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            if (uiState.messages.isEmpty()) {
                Text(
                    text = "Напишите Светлане — она ответит здесь.\nГолосовые команды работают на главном экране.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(horizontal = 40.dp)
                )
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 8.dp)
                ) {
                    items(uiState.messages, key = { it.id }) { msg ->
                        MessageBubble(msg)
                    }
                    if (uiState.isThinking) {
                        item {
                            MessageBubble(
                                ChatMessage(text = "Думаю…", isUser = false, pending = true)
                            )
                        }
                    }
                }
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
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp)
            ) {
                Column {
                    Text(
                        text = uiState.messages.lastOrNull { !it.isUser }?.text
                            ?: "Подтвердите действие",
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
                .padding(horizontal = 16.dp, vertical = 12.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp
            )
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                BasicTextField(
                    value = inputText,
                    onValueChange = { inputText = it },
                    enabled = uiState.inputEnabled,
                    textStyle = TextStyle(color = TextPrimary, fontSize = 16.sp),
                    cursorBrush = SolidColor(MintPrimary),
                    maxLines = 5,
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Sentences,
                        imeAction = ImeAction.Send
                    ),
                    keyboardActions = KeyboardActions(onSend = {
                        if (inputText.isNotBlank() && uiState.inputEnabled) {
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
                IconButton(
                    onClick = {
                        if (inputText.isNotBlank() && uiState.inputEnabled) {
                            viewModel.handleInput(context, inputText)
                            inputText = ""
                        }
                    },
                    enabled = uiState.inputEnabled && inputText.isNotBlank()
                ) {
                    Icon(
                        Icons.Outlined.Send,
                        contentDescription = "Отправить",
                        tint = if (inputText.isNotBlank()) MintPrimary else TextTertiary
                    )
                }
            }
        }
    }
}

@Composable
private fun MessageBubble(message: ChatMessage) {
    val isUser = message.isUser
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        Box(
            modifier = Modifier
                .widthIn(max = 280.dp)
                .clip(
                    RoundedCornerShape(
                        topStart = 16.dp,
                        topEnd = 16.dp,
                        bottomStart = if (isUser) 16.dp else 4.dp,
                        bottomEnd = if (isUser) 4.dp else 16.dp
                    )
                )
                .background(
                    if (isUser) MintPrimary.copy(alpha = 0.16f)
                    else MintSoft.copy(alpha = 0.08f)
                )
                .padding(horizontal = 14.dp, vertical = 10.dp)
        ) {
            Text(
                text = message.text,
                style = MaterialTheme.typography.bodyMedium,
                color = if (message.pending) TextSecondary else TextPrimary
            )
        }
    }
}
