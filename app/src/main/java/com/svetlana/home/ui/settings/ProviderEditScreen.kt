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
import com.svetlana.home.ai.providers.OpenAiCompatibleProvider
import com.svetlana.home.core.ServiceLocator
import com.svetlana.home.ui.components.GlassCard
import com.svetlana.home.ui.theme.AlmostBlack
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

    LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            GlassCard(modifier = Modifier.fillMaxWidth()) {
                Column {
                    Text("Подключение провайдера", style = MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = name, onValueChange = { name = it },
                        label = { Text("Название") },
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
                        ActionChip("Проверить подключение") {
                            if (baseUrl.isBlank() || apiKey.isBlank()) {
                                connectionStatus = "Укажите endpoint и API Key"
                                return@ActionChip
                            }
                            isWorking = true
                            connectionStatus = "Проверяю…"
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
                                        "✓ Подключено: ${config.name} @ $baseUrl"
                                    else "✕ Ошибка: ${r.text.take(100)}"
                                }
                            }
                        }
                        ActionChip("Получить модели") {
                            if (baseUrl.isBlank() || apiKey.isBlank()) {
                                connectionStatus = "Сначала проверьте подключение"
                                return@ActionChip
                            }
                            isWorking = true
                            connectionStatus = "Запрашиваю модели…"
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
                                        "Не удалось получить список моделей"
                                    else "Получено моделей: ${models.size}"
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

        // Реальный список моделей с сервера
        if (remoteModels.isNotEmpty()) {
            item {
                Text("Доступные модели (с сервера)", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(6.dp))
            }
            items(remoteModels.size) { i ->
                val m = remoteModels[i]
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (model == m) MintPrimary.copy(alpha = 0.15f) else AlmostBlack)
                        .clickable { model = m; verifiedModel = null; modelTestStatus = "" }
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(if (model == m) "●" else "○", color = MintPrimary)
                    Spacer(Modifier.height(0.dp))
                    Text(m, modifier = Modifier.weight(1f).padding(start = 8.dp),
                        style = MaterialTheme.typography.bodyMedium)
                    if (verifiedModel == m) Text("✓", color = MintPrimary)
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ActionChip("Проверить модель") {
                        if (model.isBlank()) {
                            modelTestStatus = "Выберите модель из списка"
                            return@ActionChip
                        }
                        isWorking = true
                        modelTestStatus = "Тестирую inference…"
                        scope.launch(Dispatchers.IO) {
                            val provider = OpenAiCompatibleProvider(
                                ServiceLocator.context(), config.copy(baseUrl = baseUrl, model = model)
                            )
                            manager.setApiKey(config.id, apiKey)
                            val r: AIResult = provider.testModel(model)
                            withContext(Dispatchers.Main) {
                                isWorking = false
                                modelTestStatus = if (r.success) {
                                    verifiedModel = model
                                    "✓ Модель «$model» ответила: «${r.text.take(40)}» (${r.latencyMs}мс)"
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
                colors = ButtonDefaults.buttonColors(containerColor = MintPrimary)
            ) { Text("Сохранить", color = AlmostBlack) }
        }

        item {
            Text(
                text = "Ключ хранится в Android Keystore и не попадает в репозиторий. " +
                        "Прямой ключ OpenAI в мобильном приложении виден ему — " +
                        "для production используйте Personal Gateway.",
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary
            )
        }
    }
}
