package com.svetlana.home.ui.settings

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.svetlana.home.R
import com.svetlana.home.ai.AIMode
import com.svetlana.home.ai.AIModel
import com.svetlana.home.ai.CompatibilityLevel
import com.svetlana.home.ai.ModelVerificationRunner
import com.svetlana.home.ai.ProviderConfig
import com.svetlana.home.core.SvetlanaStatus
import com.svetlana.home.core.ServiceLocator
import com.svetlana.home.memory.HistoryCategory
import com.svetlana.home.translate.TranslateDirection
import com.svetlana.home.ui.components.GlassCard
import com.svetlana.home.ui.theme.AlmostBlack
import com.svetlana.home.ui.theme.MintPrimary
import com.svetlana.home.ui.theme.MintSoft
import com.svetlana.home.ui.theme.TextTertiary
import com.svetlana.home.ui.theme.WarnAmber
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.first
import com.svetlana.home.ai.BenchmarkRunner

/**
 * Провайдеры ИИ (ТЗ §36, §38, §88).
 * Пользователь сам выбирает провайдера и видит, где выполняется запрос.
 */
@Composable
fun AiProvidersScreen() {
    val context = LocalContext.current
    val settings = ServiceLocator.settings
    val manager = remember { ServiceLocator.providerManager }
    val scope = rememberCoroutineScope()
    var configs by remember { mutableStateOf(manager.list()) }
    var activeId by remember { mutableStateOf<String?>(null) }
    var mode by remember { mutableStateOf(AIMode.AUTO) }
    var editing by remember { mutableStateOf<ProviderConfig?>(null) }

    LaunchedEffect(Unit) {
        activeId = settings.activeProviderId.first()
        mode = settings.aiMode.first()
    }

    // Полная цепочка настройки провайдера (аудит п.1)
    editing?.let { cfg ->
        ProviderEditScreen(config = cfg, onSaved = {
            editing = null
            configs = manager.list()
        })
        return
    }

    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Text(
                text = stringResource(R.string.provider_secured),
                style = MaterialTheme.typography.bodySmall
            )
        }
        item {
            Text("Режим AI", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(6.dp))
            AIMode.entries.forEach { m ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (mode == m) MintPrimary.copy(alpha = 0.15f) else AlmostBlack)
                        .clickable {
                            mode = m
                            scope.launch { settings.setAiMode(m) }
                        }
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(m.label, modifier = Modifier.weight(1f))
                    if (mode == m) Text("✓", color = MintPrimary)
                }
            }
        }
        item {
            Spacer(Modifier.height(8.dp))
            Text("Подключённые провайдеры", style = MaterialTheme.typography.titleMedium)
        }
        items(configs.size) { i ->
            val cfg = configs[i]
            GlassCard(modifier = Modifier.fillMaxWidth()) {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(cfg.name, style = MaterialTheme.typography.titleMedium)
                            Text("${cfg.baseUrl} · модель: ${cfg.model}",
                                style = MaterialTheme.typography.bodySmall)
                            Text(
                                text = if (activeId == cfg.id) "Основной AI" else stringResource(R.string.provider_status),
                                style = MaterialTheme.typography.labelLarge
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ActionChip("Настроить") {
                            editing = cfg
                        }
                        ActionChip(stringResource(R.string.provider_test)) {
                            scope.launch(Dispatchers.IO) {
                                val r = manager.build(cfg).testConnection()
                                withContext(Dispatchers.Main) {
                                    Toast.makeText(context, if (r.success) "Соединение OK" else "Ошибка: ${r.text.take(80)}",
                                        Toast.LENGTH_LONG).show()
                                }
                            }
                        }
                        if (activeId != cfg.id) {
                            ActionChip(stringResource(R.string.provider_use)) {
                                activeId = cfg.id
                                scope.launch { settings.setActiveProvider(cfg.id) }
                            }
                        } else {
                            ActionChip(stringResource(R.string.provider_disable)) {
                                activeId = null
                                scope.launch { settings.setActiveProvider(null) }
                            }
                        }
                        ActionChip(stringResource(R.string.provider_delete)) {
                            manager.remove(cfg.id)
                            configs = manager.list()
                        }
                    }
                }
            }
        }
        item {
            Text("Добавить провайдера из шаблонов", style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = 8.dp))
            Spacer(Modifier.height(6.dp))
            ProviderConfig.presets.forEach { preset ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            if (manager.add(preset)) configs = manager.list()
                        }
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("+ ${preset.name}", modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium)
                    Text(preset.baseUrl, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        item {
            Text(
                text = if (configs.isEmpty()) stringResource(R.string.provider_none)
                else "Внешние AI не обязательны: launcher, Hands и переводчик работают и без них.",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

@Composable
fun ActionChip(text: String, onClick: () -> Unit) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(MintPrimary.copy(alpha = 0.12f))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp)
    )
}

/**
 * Локальный ИИ (ТЗ §34). Скачать можно только по решению пользователя.
 */
@Composable
fun LocalAiScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val registry = remember { ServiceLocator.modelRegistry }
    val manager = remember { ServiceLocator.localModelManager }
    val llamaRuntime = remember { ServiceLocator.llamaRuntime }
    val compat = remember { ServiceLocator.compatibility }
    val settings = ServiceLocator.settings
    var installed by remember { mutableStateOf(manager.list()) }
    var activeModelId by remember { mutableStateOf<String?>(null) }
    var downloading by remember { mutableStateOf<String?>(null) }
    val reports = remember { compat.compatibleModels(registry) }
    // ТЗ §35/аудит п.2: автоматическая проверка модели после установки.
    var verification by remember { mutableStateOf<ModelVerificationRunner.Report?>(null) }
    var verifying by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) { activeModelId = settings.activeLocalModelId.first() }

    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Text(
                text = stringResource(R.string.model_never_auto),
                style = MaterialTheme.typography.bodySmall
            )
        }

        if (installed.isEmpty()) {
            item {
                GlassCard {
                    Text(stringResource(R.string.model_not_installed),
                        style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = stringResource(R.string.model_offer_question),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }

        item {
            Text(stringResource(R.string.models_installed), style = MaterialTheme.typography.titleMedium)
        }
        if (installed.isEmpty()) {
            item { Text("Список пуст — это нормальное состояние.", style = MaterialTheme.typography.bodySmall) }
        }
        items(installed.size) { i ->
            val model = installed[i]
            GlassCard {
                Column {
                    Text(model.name, style = MaterialTheme.typography.titleMedium)
                    Text("${model.sizeBytes / (1024 * 1024)} МБ",
                        style = MaterialTheme.typography.bodySmall)
                    Text("Статус: ${manager.benchmarkStatusFor(model.modelId)}",
                        style = MaterialTheme.typography.labelLarge)
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ActionChip(stringResource(R.string.model_benchmark)) {
                            scope.launch(Dispatchers.IO) {
                                val result = BenchmarkRunner.run(
                                    registry.byId(model.modelId)!!, manager, llamaRuntime)
                                manager.recordBenchmark(result)
                                installed = manager.list()
                                ServiceLocator.historyManager.record(HistoryCategory.MODELS,
                                    "Benchmark ${model.name}: ${result.tokensPerSecond} ток/с, ${result.status}")
                            }
                        }
                        // Аудит п.2: «Проверить модель» — реальная цепочка
                        ActionChip("Проверить модель") {
                            verifying = model.modelId
                            scope.launch(Dispatchers.IO) {
                                val report = ModelVerificationRunner.verify(
                                    model.modelId, manager, registry, llamaRuntime,
                                    ServiceLocator.device)
                                withContext(Dispatchers.Main) {
                                    verification = report
                                    verifying = null
                                    ServiceLocator.historyManager.record(HistoryCategory.MODELS,
                                        "Проверка ${model.name}: " +
                                                if (report.inferenceOk) "inference OK, ${report.tokensPerSecond} ток/с"
                                                else "inference не запущен: ${report.failureReason}")
                                }
                            }
                        }
                        ActionChip(stringResource(R.string.model_delete)) {
                            manager.uninstall(model.modelId)
                            installed = manager.list()
                            verification = null
                        }
                    }
                    // Результат проверки модели (аудит п.2)
                    if (verifying == model.modelId) {
                        Spacer(Modifier.height(8.dp))
                        Text("Проверяю модель…", style = MaterialTheme.typography.bodySmall)
                    }
                    verification?.takeIf { it.modelId == model.modelId }?.let { rep ->
                        Spacer(Modifier.height(8.dp))
                        Text(rep.summary(), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }

        item {
            Text(stringResource(R.string.models_compatible), style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = 8.dp))
        }
        items(reports.size) { i ->
            val report = reports[i]
            val alreadyInstalled = installed.any { it.modelId == report.model.id }
            GlassCard {
                Column {
                    Text(report.model.name, style = MaterialTheme.typography.titleMedium)
                    Text("${stringResource(R.string.model_size)}: ${report.model.sizeMb} МБ · " +
                            "${stringResource(R.string.model_ram_required)}: ${report.model.ramRequirementMb} МБ",
                        style = MaterialTheme.typography.bodySmall)
                    // ТЗ §33: всегда показываем свободное место — пользователь
                    // видит, займёт ли модель слишком много storage.
                    Text("${stringResource(R.string.model_free_space)}: ${manager.freeSpaceBytes() / (1024 * 1024)} МБ",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (report.model.sizeMb > manager.freeSpaceBytes() / (1024 * 1024))
                            WarnAmber else TextTertiary)
                    Text(report.reasons.joinToString("\n"), style = MaterialTheme.typography.bodySmall)
                    Text("${stringResource(R.string.model_perf)}: ${report.expectedPerf}",
                        style = MaterialTheme.typography.bodySmall)
                    Text("Совместимость: ${report.level.label}", style = MaterialTheme.typography.labelLarge)
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (downloading == report.model.id) {
                            Text("Загрузка… ${report.model.sizeMb} МБ", style = MaterialTheme.typography.bodySmall)
                        } else if (!alreadyInstalled) {
                            ActionChip(stringResource(R.string.model_download)) {
                                // Только по решению пользователя (ТЗ §32/§87)
                                downloading = report.model.id
                                scope.launch(Dispatchers.IO) {
                                    val result = manager.install(report.model) { percent ->
                                        // прогресс можно показать в live-состоянии
                                    }
                                    downloading = null
                                    installed = manager.list()
                                    // Аудит п.2: после установки автоматически
                                    // проверяем модель — «установлена» ≠ «работает».
                                    val installedModel = result.getOrNull()
                                    if (installedModel != null) {
                                        val rep = ModelVerificationRunner.verify(
                                            installedModel.modelId, manager, registry,
                                            llamaRuntime, ServiceLocator.device)
                                        withContext(Dispatchers.Main) {
                                            verification = rep
                                            ServiceLocator.historyManager.record(
                                                HistoryCategory.MODELS,
                                                "Проверка после установки ${installedModel.name}: " +
                                                    if (rep.inferenceOk)
                                                        "inference OK, ${rep.tokensPerSecond} ток/с"
                                                    else "inference не запущен: ${rep.failureReason}")
                                        }
                                    } else {
                                        withContext(Dispatchers.Main) {
                                            Toast.makeText(context,
                                                if (result.isSuccess) "Модель загружена" else "Ошибка загрузки",
                                                Toast.LENGTH_LONG).show()
                                        }
                                    }
                                }
                            }
                            ActionChip(stringResource(R.string.model_details)) { }
                        } else {
                            Text("Установлена", style = MaterialTheme.typography.labelLarge)
                        }
                        ActionChip(stringResource(R.string.model_not_now)) { }
                    }
                }
            }
        }
    }
}

@Composable
fun ModelRegistryScreen() {
    val registry = remember { ServiceLocator.modelRegistry }
    val compat = remember { ServiceLocator.compatibility }
    val reports = remember { registry.all().map { compat.evaluate(it) } }

    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(reports.size) { i ->
            val r = reports[i]
            GlassCard {
                Column {
                    Text(r.model.name, style = MaterialTheme.typography.titleMedium)
                    Text("${r.model.parameters} · ${r.model.quantization} · ${r.model.backend}",
                        style = MaterialTheme.typography.bodySmall)
                    Text("Контекст: ${if (r.model.context > 0) r.model.context else "—"} · " +
                            "Лицензия: ${r.model.license}",
                        style = MaterialTheme.typography.bodySmall)
                    Text("Источник: ${r.model.source}", style = MaterialTheme.typography.bodySmall)
                    Text("Совместимость: ${r.level.label}", style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}

@Composable
fun ServerScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val manager = remember { ServiceLocator.serverManager }
    val cfg = remember { manager.config() }
    var url by remember { mutableStateOf(cfg.baseUrl) }
    var token by remember { mutableStateOf("") }
    var enabled by remember { mutableStateOf(cfg.enabled) }
    var caps by remember { mutableStateOf(com.svetlana.home.server.ServerCapabilities.unknown) }

    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Text(stringResource(R.string.server_free_tier_note),
                style = MaterialTheme.typography.bodySmall)
        }
        item {
            GlassCard {
                Column {
                    Text(stringResource(R.string.server_connect), style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = url,
                        onValueChange = { url = it },
                        label = { Text("Endpoint (https://...)") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = token,
                        onValueChange = { token = it },
                        label = { Text("Токен доступа") },
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ActionChip("Сохранить") {
                            manager.setBaseUrl(url)
                            if (token.isNotBlank()) manager.setToken(token)
                            enabled = true
                            manager.setEnabled(true)
                        }
                        ActionChip(stringResource(R.string.server_check)) {
                            scope.launch(Dispatchers.IO) {
                                val ok = manager.healthCheck()
                                caps = manager.capabilities()
                                withContext(Dispatchers.Main) {
                                    Toast.makeText(context,
                                        if (ok) "Сервер доступен" else "Сервер недоступен",
                                        Toast.LENGTH_LONG).show()
                                }
                            }
                        }
                        ActionChip(stringResource(R.string.server_disconnect)) {
                            enabled = false
                            manager.setEnabled(false)
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    // Аудит п.72: полная цепочка — connection → auth → health →
                    // capabilities → inference → latency. Проверка inference
                    // отдельной кнопкой, чтобы пользователь видел реальный
                    // ответ сервера, а не только health check.
                    var inferenceStatus by remember { mutableStateOf("") }
                    ActionChip("Проверить inference") {
                        scope.launch(Dispatchers.IO) {
                            val started = System.currentTimeMillis()
                            val reply = manager.inference("Ответь одним словом: работает.")
                            val latency = System.currentTimeMillis() - started
                            withContext(Dispatchers.Main) {
                                inferenceStatus = if (reply.isNullOrBlank()) {
                                    "✕ Inference не выполнен — сервер недоступен или не поддерживает его"
                                } else {
                                    "✓ Ответ: «${reply.take(40)}» (${latency}мс)"
                                }
                            }
                        }
                    }
                    if (inferenceStatus.isNotEmpty()) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = inferenceStatus,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (inferenceStatus.startsWith("✓")) MintPrimary else WarnAmber
                        )
                    }
                }
            }
        }
        item {
            GlassCard {
                Column {
                    Text(stringResource(R.string.server_capabilities),
                        style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(6.dp))
                    Text("CPU: ${caps.cpu}", style = MaterialTheme.typography.bodySmall)
                    Text("RAM: ${caps.ramGb} ГБ", style = MaterialTheme.typography.bodySmall)
                    Text("GPU: ${caps.gpu}", style = MaterialTheme.typography.bodySmall)
                    Text("VRAM: ${caps.vramGb} ГБ", style = MaterialTheme.typography.bodySmall)
                    Text("Inference: ${if (caps.inferenceSupported) "да" else "нет"}",
                        style = MaterialTheme.typography.bodySmall)
                    Text("Модели: ${caps.models.joinToString(", ").ifBlank { "—" }}",
                        style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "Удалённая RAM/VRAM не являются физической RAM телефона — " +
                                "это режим «Удалённая мощность».",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
    }
}

@Composable
fun TranslatorSettingsScreen() {
    val settings = ServiceLocator.settings
    val scope = rememberCoroutineScope()
    var backend by remember { mutableStateOf("auto") }
    LaunchedEffect(Unit) { backend = settings.translatorBackend.first() }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.translate_backend), style = MaterialTheme.typography.titleMedium)
        listOf("auto" to "Авто (AI, если доступен)", "ai" to "AI-перевод", "local-phrase" to "Локальный (фразы)")
            .forEach { (id, label) ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (backend == id) MintPrimary.copy(alpha = 0.15f) else AlmostBlack)
                        .clickable { backend = id; scope.launch { settings.setTranslatorBackend(id) } }
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(label, modifier = Modifier.weight(1f))
                    if (backend == id) Text("✓", color = MintPrimary)
                }
            }
        Text(
            text = "Направления: ${TranslateDirection.RU_TO_VI.label} и ${TranslateDirection.VI_TO_RU.label}",
            style = MaterialTheme.typography.bodySmall
        )
    }
}
