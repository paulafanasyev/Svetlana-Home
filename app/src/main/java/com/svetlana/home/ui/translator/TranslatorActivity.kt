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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import com.svetlana.home.core.ServiceLocator
import com.svetlana.home.core.Tr
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
    val translator = remember { RuViTranslator() }
    val speechRecognizer = remember { SvetlanaSpeechRecognizer(context.applicationContext) }
    // Текст на языке интерфейса; работает и в корутинах (не Compose-функция).
    fun t(ru: String, en: String): String = Tr.t(context, ru, en)
    val russianText = t("Русский текст", "Russian text")

    DisposableEffect(Unit) {
        onDispose { speechRecognizer.release() }
    }

    var direction by remember { mutableStateOf(TranslatorDirection.RU_TO_VI) }
    var sourceText by remember { mutableStateOf("") }
    var translatedText by remember { mutableStateOf("") }
    var status by remember { mutableStateOf(t("Проверяю модель…", "Checking the model…")) }
    var modelReady by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var speakResult by remember { mutableStateOf(true) }

    LaunchedEffect(direction) {
        modelReady = false
        status = t("Проверяю модель…", "Checking the model…")
        modelReady = runCatching { translator.isModelDownloaded(direction) }.getOrDefault(false)
        status = if (modelReady) {
            t("Модель установлена; перевод выполняется на устройстве", "Model installed; translation runs on the device")
        } else {
            t("Модель не установлена — нажмите «Скачать модель»", "Model not installed — tap “Download model”")
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .navigationBarsPadding()
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(t("Переводчик", "Translator"), style = MaterialTheme.typography.headlineMedium)
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
            Text(t("Поменять направление", "Swap direction"))
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
                        try {
                            status = t("Скачиваю модель по Wi-Fi…", "Downloading the model over Wi-Fi…")
                            val result = translator.downloadModel(direction, wifiOnly = true)
                            modelReady = result.isSuccess
                            status = result.fold(
                                onSuccess = { t("Модель установлена; интернет для перевода больше не нужен", "Model installed; translation no longer needs the internet") },
                                onFailure = { t("Не удалось скачать модель: ", "Couldn’t download the model: ") + (it.message ?: t("ошибка", "error")) }
                            )
                        } finally {
                            busy = false
                        }
                    }
                },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(t("Скачать модель", "Download model"))
            }
        }

        OutlinedTextField(
            value = sourceText,
            onValueChange = { sourceText = it },
            label = {
                Text(if (direction == TranslatorDirection.RU_TO_VI) russianText else "Tiếng Việt")
            },
            minLines = 4,
            modifier = Modifier.fillMaxWidth(),
            enabled = !busy
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Button(
                onClick = {
                    if (!speechRecognizer.isOnDeviceAvailable) {
                        status = t(
                            "Голосовой перевод требует on-device STT. Используйте текстовый режим на этом устройстве.",
                            "Voice translation needs on-device STT. Use text mode on this device."
                        )
                    } else if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                        if (context is Activity) {
                            ActivityCompat.requestPermissions(
                                context,
                                arrayOf(Manifest.permission.RECORD_AUDIO),
                                REQUEST_RECORD_AUDIO
                            )
                            status = t("Разрешите микрофон и нажмите «Говорить» ещё раз", "Allow the microphone and tap “Speak” again")
                        } else {
                            status = t("Нет разрешения на микрофон", "No microphone permission")
                        }
                    } else {
                        scope.launch {
                            busy = true
                            try {
                                status = t("Слушаю…", "Listening…")
                                speechRecognizer.startListening(direction.sourceLocale)
                                val result = speechRecognizer.awaitResult(9_000L)
                                when (result) {
                                    is SvetlanaSpeechRecognizer.SttResult.Success -> {
                                        sourceText = result.text
                                        if (result.text.isBlank()) {
                                            status = t("Речь не распознана", "Speech not recognized")
                                        } else if (!modelReady) {
                                            status = t("Сначала скачайте модель перевода", "Download the translation model first")
                                        } else {
                                            val translated = translator.translate(result.text, direction)
                                            translatedText = translated.getOrDefault("")
                                            status = translated.fold(
                                                onSuccess = { t("Переведено локально", "Translated on the device") },
                                                onFailure = { it.message ?: t("Ошибка перевода", "Translation error") }
                                            )
                                            if (translated.isSuccess && speakResult) {
                                                ServiceLocator.tts.speak(
                                                    translated.getOrThrow(),
                                                    direction.targetLocale
                                                )
                                            }
                                        }
                                    }
                                    is SvetlanaSpeechRecognizer.SttResult.Error -> status = result.message
                                    null -> status = t("Не удалось дождаться распознавания", "Recognition timed out")
                                }
                            } catch (t2: Throwable) {
                                status = t2.message ?: t("Ошибка голосового перевода", "Voice translation error")
                            } finally {
                                speechRecognizer.finishSession()
                                busy = false
                            }
                        }
                    }
                },
                enabled = !busy
            ) {
                Text(t("Говорить", "Speak"))
            }

            Button(
                onClick = {
                    scope.launch {
                        busy = true
                        try {
                            status = t("Перевожу локально…", "Translating on the device…")
                            val result = translator.translate(sourceText, direction)
                            translatedText = result.getOrDefault("")
                            status = result.fold(
                                onSuccess = { t("Переведено локально", "Translated on the device") },
                                onFailure = { it.message ?: t("Ошибка перевода", "Translation error") }
                            )
                            if (result.isSuccess && speakResult) {
                                ServiceLocator.tts.speak(
                                    result.getOrThrow(),
                                    direction.targetLocale
                                )
                            }
                        } finally {
                            busy = false
                        }
                    }
                },
                enabled = !busy && modelReady && sourceText.isNotBlank(),
                modifier = Modifier.weight(1f)
            ) {
                Text(t("Перевести", "Translate"))
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(t("Озвучивать результат", "Read the result aloud"), style = MaterialTheme.typography.bodyMedium)
            Switch(checked = speakResult, onCheckedChange = { speakResult = it })
        }

        OutlinedTextField(
            value = translatedText,
            onValueChange = {},
            label = {
                Text(if (direction == TranslatorDirection.RU_TO_VI) "Tiếng Việt" else russianText)
            },
            minLines = 4,
            readOnly = true,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(Modifier.height(8.dp))
        OutlinedButton(
            onClick = {
                scope.launch {
                    busy = true
                    try {
                        status = t("Удаляю модель…", "Deleting the model…")
                        val result = translator.deleteModel(direction)
                        modelReady = false
                        translatedText = ""
                        status = result.fold(
                            onSuccess = { t("Модель удалена", "Model deleted") },
                            onFailure = { it.message ?: t("Не удалось удалить модель", "Couldn’t delete the model") }
                        )
                    } finally {
                        busy = false
                    }
                }
            },
            enabled = !busy && modelReady,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(t("Удалить модель направления", "Delete this direction’s model"))
        }

        Text(
            t(
                "После загрузки модели перевод не использует внешний AI-сервер. Скачивание модели выполняется только после нажатия кнопки.",
                "Once the model is downloaded, translation doesn’t use an external AI server. The model is downloaded only when you tap the button."
            ),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outlineVariant
        )
    }
}

private const val REQUEST_RECORD_AUDIO = 401
