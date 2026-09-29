package com.svetlana.home.ui.translator

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import com.svetlana.home.core.ServiceLocator
import com.svetlana.home.translator.RuViTranslator
import com.svetlana.home.translator.TranslatorDirection
import com.svetlana.home.ui.theme.SvetlanaSettingsTheme
import com.svetlana.home.voice.SvetlanaSpeechRecognizer
import kotlinx.coroutines.launch

class TranslatorActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            SvetlanaSettingsTheme {
                TranslatorScreen()
            }
        }
    }
}

@Composable
private fun TranslatorScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val translator = remember { RuViTranslator(context.applicationContext) }
    val speechRecognizer = remember { SvetlanaSpeechRecognizer(context.applicationContext) }
    DisposableEffect(Unit) {
        onDispose { speechRecognizer.release() }
    }

    var direction by remember { mutableStateOf(TranslatorDirection.RU_TO_VI) }
    var sourceText by remember { mutableStateOf("") }
    var translatedText by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("Проверяю модель…") }
    var modelReady by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var speakResult by remember { mutableStateOf(true) }

    fun refreshModelState() {
        scope.launch {
            modelReady = false
            status = "Проверяю модель…"
            modelReady = runCatching { translator.isModelDownloaded(direction) }.getOrDefault(false)
            status = if (modelReady) "Модель установлена; перевод выполняется на устройстве" else
                "Модель не установлена — нажмите «Скачать модель»"
        }
    }

    androidx.compose.runtime.LaunchedEffect(direction) { refreshModelState() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .navigationBarsPadding()
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Переводчик", style = MaterialTheme.typography.headlineMedium)
        Text(
            text = direction.label,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center
        )

        Button(
            onClick = {
                direction = when (direction) {
                    TranslatorDirection.RU_TO_VI -> TranslatorDirection.VI_TO_RU
                    TranslatorDirection.VI_TO_RU -> TranslatorDirection.RU_TO_VI
                }
                sourceText = ""
                translatedText = ""
            },
            enabled = !busy,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Поменять направление")
        }

        Text(
            text = status,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        if (!modelReady) {
            OutlinedButton(
                onClick = {
                    scope.launch {
                        busy = true
                        status = "Скачиваю модель по Wi-Fi…"
                        val result = translator.downloadModel(direction, wifiOnly = true)
                        modelReady = result.isSuccess
                        status = result.fold(
                            onSuccess = { "Модель установлена; интернет для перевода больше не нужен" },
                            onFailure = { "Не удалось скачать модель: " + (it.message ?: "ошибка") }
                        )
                        busy = false
                    }
                },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Скачать модель")
            }
        }

        OutlinedTextField(
            value = sourceText,
            onValueChange = { sourceText = it },
            label = { Text(if (direction == TranslatorDirection.RU_TO_VI) "Русский текст" else "Tiếng Việt") },
            minLines = 4,
            modifier = Modifier.fillMaxWidth(),
            enabled = !busy
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Button(
                onClick = {
                    if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                        if (context is Activity) {
                            ActivityCompat.requestPermissions(
                                context,
                                arrayOf(Manifest.permission.RECORD_AUDIO),
                                401
                            )
                            status = "Разрешите микрофон и нажмите «Говорить» ещё раз"
                        } else {
                            status = "Нет разрешения на микрофон"
                        }
                        return@Button
                    }
                    scope.launch {
                        busy = true
                        status = "Слушаю…"
                        speechRecognizer.startListening(direction.sourceLocale)
                        val result = speechRecognizer.awaitResult(9_000L)
                        speechRecognizer.finishSession()
                        when (result) {
                            is SvetlanaSpeechRecognizer.SttResult.Success -> {
                                if (result.text.isBlank()) {
                                    status = "Речь не распознана"
                                } else {
                                    sourceText = result.text
                                    if (!modelReady) {
                                        status = "Сначала скачайте модель перевода"
                                    } else {
                                        val translated = translator.translate(result.text, direction)
                                        translatedText = translated.getOrDefault("")
                                        status = translated.fold(
                                            onSuccess = { "Переведено локально" },
                                            onFailure = { it.message ?: "Ошибка перевода" }
                                        )
                                        if (translated.isSuccess && speakResult) {
                                            ServiceLocator.tts.speak(translated.getOrThrow(), direction.targetLocale)
                                        }
                                    }
                                }
                            }
                            is SvetlanaSpeechRecognizer.SttResult.Error -> status = result.message
                            null -> status = "Не удалось дождаться распознавания"
                        }
                        busy = false
                    }
                },
                enabled = !busy,
                modifier = Modifier
            ) {
                Text("Говорить")
            }

            Button(
                onClick = {
                    scope.launch {
                        busy = true
                        status = "Перевожу локально…"
                        val result = translator.translate(sourceText, direction)
                        translatedText = result.getOrDefault("")
                        status = result.fold(
                            onSuccess = { "Переведено локально" },
                            onFailure = { it.message ?: "Ошибка перевода" }
                        )
                        if (result.isSuccess && speakResult) {
                            ServiceLocator.tts.speak(result.getOrThrow(), direction.targetLocale)
                        }
                        busy = false
                    }
                },
                enabled = !busy && modelReady && sourceText.isNotBlank()
            ) {
                Text("Перевести")
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("Озвучивать результат", style = MaterialTheme.typography.bodyMedium)
            Switch(checked = speakResult, onCheckedChange = { speakResult = it })
        }

        OutlinedTextField(
            value = translatedText,
            onValueChange = {},
            label = { Text(if (direction == TranslatorDirection.RU_TO_VI) "Tiếng Việt" else "Русский текст") },
            minLines = 4,
            readOnly = true,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(Modifier.height(8.dp))
        OutlinedButton(
            onClick = {
                scope.launch {
                    busy = true
                    status = "Удаляю модель…"
                    val result = translator.deleteModel(direction)
                    modelReady = false
                    status = result.fold(
                        onSuccess = { "Модель удалена" },
                        onFailure = { it.message ?: "Не удалось удалить модель" }
                    )
                    busy = false
                }
            },
            enabled = !busy && modelReady,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Удалить модель направления")
        }

        Text(
            "После загрузки модели перевод не использует внешний AI-сервер.                 "Скачивание модели выполняется только после нажатия кнопки.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outlineVariant
        )
    }
}
