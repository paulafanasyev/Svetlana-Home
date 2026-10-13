package com.svetlana.home.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.svetlana.home.R
import com.svetlana.home.ai.AIResult
import com.svetlana.home.ai.ProviderConfig
import com.svetlana.home.ai.ProviderState
import com.svetlana.home.ai.ProviderStateCalculator
import com.svetlana.home.ai.providers.OpenAiCompatibleProvider
import com.svetlana.home.core.ServiceLocator
import com.svetlana.home.core.tr
import com.svetlana.home.memory.HistoryCategory
import com.svetlana.home.ui.components.GlassCard
import com.svetlana.home.ui.theme.MintPrimary
import com.svetlana.home.ui.theme.TextSecondary
import com.svetlana.home.ui.theme.WarnAmber
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Полная цепочка настройки внешнего AI-провайдера (аудит п.1):
 *
 *   Провайдер → Endpoint → API Key →
 *   [Проверить подключение] →
 *   [Получить модели] → список реальных моделей с сервера →
 *   выбор модели → [Проверить модель] (реальный inference) →
 *   [Сохранить].
 *
 * HTTP 200 на /models не считается успехом: успех — это реальный ответ
 * выбранной модели на тестовый запрос.
 *
 * Безопасность ключа (ТЗ §38): ключ хранится в SecureKeyStore (Android
 * Keystore) и не попадает в репозиторий. Для мобильной сборки предупредим,
 * что прямой ключ OpenAI виден приложению — для production рекомендуется
 * Personal Gateway.
 */
@Composable
fun ProviderEditScreen(config: ProviderConfig, onSaved: () -> Unit) {
    val scope = rememberCoroutineScope()
    val manager = remember { ServiceLocator.providerManager }

    var name by remember { mutableStateOf(config.name) }
    var baseUrl by remember { mutableStateOf(config.baseUrl) }
    var apiKey by remember { mutableStateOf(manager.apiKeyFor(config.id).orEmpty()) }
    var model by remember { mutableStateOf(config.model) }

    val remoteModels = remember { mutableStateListOf<String>() }
    var connectionStatus by remember { mutableStateOf("") }
    var modelTestStatus by remember { mutableStateOf("") }
    var isWorking by remember { mutableStateOf(false) }
    var verifiedModel by remember { mutableStateOf<String?>(null) }

    // Тексты для обработчиков нажатий (вне Compose-контекста).
    val needEndpointKey = tr("Укажите endpoint и API Key", "Enter the endpoint and API key")
    val checking = tr("Проверяю…", "Checking…")
    val connected = tr("Подключено", "Connected")
    val errorWord = tr("Ошибка", "Error")
    val checkFirst = tr("Сначала проверьте подключение", "Check the connection first")
    val fetchingModels = tr("Запрашиваю модели…", "Fetching models…")
    val noModels = tr("Не удалось получить список моделей", "Couldn’t get the model list")
    val modelsGot = tr("Получено моделей", "Models received")
    val needModel = tr("Укажите модель", "Enter a model")
    val testingInference = tr("Тестирую inference…", "Testing inference…")
    val modelWord = tr("Модель", "Model")
    val answered = tr("ответила", "replied")
    val msWord = tr("мс", "ms")

    LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            GlassCard(modifier = Modifier.fillMaxWidth()) {
                Column {
                    Text(tr("Подключение провайдера", "Provider connection"), style = MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = name, onValueChange = { name = it },
                        label = { Text(tr("Название", "Name")) },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = baseUrl, onValueChange = { baseUrl = it },
                        label = { Text("Endpoint (https://...)") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = apiKey, onValueChange = { apiKey = it },
                        label = { Text("API Key") },
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ActionChip(tr("Проверить подключение", "Test connection")) {
                            if (baseUrl.isBlank() || apiKey.isBlank()) {
                                connectionStatus = needEndpointKey
                                return@ActionChip
                            }
                            isWorking = true
                            connectionStatus = checking
                            scope.launch(Dispatchers.IO) {
                                // Сохраняем промежуточную конфигурацию, чтобы
                                // провайдер мог прочитать ключ из Keystore.
                                manager.update(config.copy(name = name, baseUrl = baseUrl, model = model))
                                manager.setApiKey(config.id, apiKey)
                                val provider = OpenAiCompatibleProvider(
                                    ServiceLocator.context(), config.copy(baseUrl = baseUrl, model = model)
                                )
                                val r = provider.testConnection()
                                withContext(Dispatchers.Main) {
                                    isWorking = false
                                    connectionStatus = if (r.success)
                                        "✓ $connected: ${config.name} @ $baseUrl"
                                    else "✕ $errorWord: ${r.text.take(100)}"
                                }
                            }
                        }
                        ActionChip(tr("Получить модели", "Get models")) {
                            if (baseUrl.isBlank() || apiKey.isBlank()) {
                                connectionStatus = checkFirst
                                return@ActionChip
                            }
                            isWorking = true
                            connectionStatus = fetchingModels
                            scope.launch(Dispatchers.IO) {
                                manager.update(config.copy(name = name, baseUrl = baseUrl, model = model))
                                manager.setApiKey(config.id, apiKey)
                                val provider = OpenAiCompatibleProvider(
                                    ServiceLocator.context(), config.copy(baseUrl = baseUrl, model = model)
                                )
                                val models = provider.listModels()
                                withContext(Dispatchers.Main) {
                                    isWorking = false
                                    remoteModels.clear()
                                    remoteModels.addAll(models)
                                    connectionStatus = if (models.isEmpty())
                                        noModels
                                    else "$modelsGot: ${models.size}"
                                }
                            }
                        }
                    }
                    if (connectionStatus.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        Text(connectionStatus, style = MaterialTheme.typography.bodySmall,
                            color = if (connectionStatus.startsWith("✓")) MintPrimary
                                    else if (connectionStatus.startsWith("✕")) WarnAmber
                                    else TextSecondary)
                    }
                }
            }
        }

        // Ручной ввод модели: не все провайдеры отдают /models, а пользователь
        // может знать название модели из документации (аудит п.3 —
        // «нет выбора моделей»). Поле всегда доступно.
        item {
            GlassCard(modifier = Modifier.fillMaxWidth()) {
                Column {
                    Text(modelWord, style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(6.dp))
                    OutlinedTextField(
                        value = model, onValueChange = {
                            model = it
                            verifiedModel = null
                            modelTestStatus = ""
                        },
                        label = { Text(tr("Например: gpt-4o-mini", "For example: gpt-4o-mini")) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = tr(
                            "Можно ввести вручную или выбрать из списка ниже после «Получить модели».",
                            "Type it in or pick one from the list below after “Get models”."
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ActionChip(tr("Проверить модель", "Test model")) {
                            if (model.isBlank()) {
                                modelTestStatus = needModel
                                return@ActionChip
                            }
                            isWorking = true
                            modelTestStatus = testingInference
                            scope.launch(Dispatchers.IO) {
                                manager.update(config.copy(name = name, baseUrl = baseUrl, model = model))
                                manager.setApiKey(config.id, apiKey)
                                val provider = OpenAiCompatibleProvider(
                                    ServiceLocator.context(), config.copy(baseUrl = baseUrl, model = model)
                                )
                                val r: AIResult = provider.testModel(model)
                                withContext(Dispatchers.Main) {
                                    isWorking = false
                                    modelTestStatus = if (r.success) {
                                        verifiedModel = model
                                        "✓ $modelWord «$model» $answered: «${r.text.take(40)}» (${r.latencyMs}$msWord)"
                                    } else "✕ ${r.text.take(100)}"
                                }
                            }
                        }
                    }
                    if (modelTestStatus.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        Text(modelTestStatus, style = MaterialTheme.typography.bodySmall,
                            color = if (modelTestStatus.startsWith("✓")) MintPrimary else WarnAmber)
                    }
                }
            }
        }

        // Реальный список моделей с сервера
        if (remoteModels.isNotEmpty()) {
            item {
                Text(tr("Доступные модели (с сервера)", "Available models (from the server)"), style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(6.dp))
            }
            items(remoteModels.size) { i ->
                val m = remoteModels[i]
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (model == m) MintPrimary.copy(alpha = 0.15f) else MaterialTheme.colorScheme.surfaceVariant)
                        .clickable { model = m; verifiedModel = null; modelTestStatus = "" }
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(if (model == m) "●" else "○", color = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(0.dp))
                    Text(m, modifier = Modifier.weight(1f).padding(start = 8.dp),
                        style = MaterialTheme.typography.bodyMedium)
                    if (verifiedModel == m) Text("✓", color = MaterialTheme.colorScheme.primary)
                }
            }
            item {
                Text(
                    text = tr(
                        "Выберите модель из списка, затем проверьте её кнопкой выше.",
                        "Pick a model from the list, then test it with the button above."
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        item {
            // Аудит §23: состояние провайдера вычисляется из фактов.
            val state = ProviderStateCalculator.from(
                configured = baseUrl.isNotBlank() && apiKey.isNotBlank(),
                connectionOk = connectionStatus.startsWith("✓"),
                modelExists = remoteModels.isNotEmpty() || model.isNotBlank(),
                inferenceOk = modelTestStatus.startsWith("✓"),
                isActive = false
            )
            GlassCard(modifier = Modifier.fillMaxWidth()) {
                Column {
                    Text(tr("Состояние", "State") + ": ${state.label}", style = MaterialTheme.typography.titleMedium,
                        color = if (state == ProviderState.VERIFIED || state == ProviderState.ACTIVE)
                            MintPrimary else TextSecondary)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = when (state) {
                            ProviderState.CONFIGURED -> tr(
                                "Конфигурация сохранена, но inference не проверен. " +
                                    "Активировать можно только после проверки модели.",
                                "Configuration saved, but inference isn’t tested. " +
                                    "You can activate only after the model test."
                            )
                            ProviderState.CONNECTION_FAILED -> tr(
                                "Соединение не установилось. Проверьте endpoint и ключ.",
                                "Couldn’t connect. Check the endpoint and key."
                            )
                            ProviderState.CONNECTED -> tr(
                                "Соединение есть. Теперь проверьте модель — реальный тестовый запрос.",
                                "Connected. Now test the model with a real test request."
                            )
                            ProviderState.VERIFIED -> tr(
                                "✓ Модель ответила на тестовый запрос. Можно активировать.",
                                "✓ The model answered the test request. You can activate it."
                            )
                            ProviderState.ACTIVE -> tr("✓ Провайдер выбран основным.", "✓ This provider is the main one.")
                            ProviderState.CHECKING -> tr("Идёт проверка…", "Checking…")
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        item {
            Button(
                onClick = {
                    scope.launch(Dispatchers.IO) {
                        manager.update(config.copy(name = name, baseUrl = baseUrl, model = model))
                        if (apiKey.isNotBlank()) manager.setApiKey(config.id, apiKey)
                        withContext(Dispatchers.Main) { onSaved() }
                    }
                },
                enabled = !isWorking && baseUrl.isNotBlank() && apiKey.isNotBlank() && model.isNotBlank(),
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
            ) { Text(tr("Сохранить", "Save"), color = MaterialTheme.colorScheme.onPrimary) }
        }

        item {
            // Аудит §23: ACTIVATE разрешён только после реального inference.
            // Кнопка явно отключена, пока модель не ответила на тестовый запрос.
            Button(
                onClick = {
                    scope.launch(Dispatchers.IO) {
                        // Фикс «круга» (аудит пользователя): после настройки
                        // провайдер не становился активным, а режим оставался
                        // LOCAL — чат бесконечно просил «настройте внешний ИИ».
                        ServiceLocator.settings.setActiveProvider(config.id)
                        ServiceLocator.settings.setAiMode(com.svetlana.home.ai.AIMode.EXTERNAL)
                        ServiceLocator.historyManager.record(HistoryCategory.AI,
                            "Активирован провайдер $name (${model})")
                        withContext(Dispatchers.Main) { onSaved() }
                    }
                },
                enabled = !isWorking && modelTestStatus.startsWith("✓"),
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MintPrimary)
            ) { Text(tr("Активировать провайдера", "Activate provider"), color = MaterialTheme.colorScheme.onPrimary) }
            if (!modelTestStatus.startsWith("✓")) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = tr(
                        "Активация доступна после успешной проверки модели «Проверить модель».",
                        "Activation is available after a successful “Test model”."
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )
            }
        }

        item {
            Text(
                text = tr(
                    "Ключ хранится в Android Keystore и не попадает в репозиторий. " +
                        "Прямой ключ OpenAI в мобильном приложении виден ему — " +
                        "для production используйте Personal Gateway.",
                    "The key is stored in Android Keystore and never goes into the repo. " +
                        "A direct OpenAI key is visible to the mobile app — " +
                        "use a Personal Gateway in production."
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
