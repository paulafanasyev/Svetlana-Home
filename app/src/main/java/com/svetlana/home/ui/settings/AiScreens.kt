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
import com.svetlana.home.ai.ModelDownloads
import com.svetlana.home.ai.ModelVerificationRunner
import com.svetlana.home.ai.ProviderConfig
import com.svetlana.home.core.SvetlanaStatus
import com.svetlana.home.core.ServiceLocator
import com.svetlana.home.core.tr
import com.svetlana.home.core.trLabel
import com.svetlana.home.memory.HistoryCategory
import com.svetlana.home.ui.components.GlassCard
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
    val connOk = tr("Соединение OK", "Connection OK")
    val errorWord = tr("Ошибка", "Error")

    LaunchedEffect(Unit) {
        activeId = settings.activeProviderId.first()
        mode = settings.aiMode.first()
    }

    // Полная цепочка настройки провайдера (аудит п.1)
    editing?.let { cfg ->
        ProviderEditScreen(config = cfg, onSaved = {
            editing = null
            configs = manager.list()
            scope.launch {
                activeId = settings.activeProviderId.first()
                mode = settings.aiMode.first()
            }
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
            Text(tr("Режим AI", "AI mode"), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(6.dp))
            AIMode.entries.forEach { m ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (mode == m) MintPrimary.copy(alpha = 0.15f) else MaterialTheme.colorScheme.surfaceVariant)
                        .clickable {
                            mode = m
                            scope.launch { settings.setAiMode(m) }
                        }
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(trLabel(m.label), modifier = Modifier.weight(1f))
                    if (mode == m) Text("✓", color = MaterialTheme.colorScheme.primary)
                }
            }
        }
        item {
            Spacer(Modifier.height(8.dp))
            Text(tr("Подключённые провайдеры", "Connected providers"), style = MaterialTheme.typography.titleMedium)
        }
        items(configs.size) { i ->
            val cfg = configs[i]
            GlassCard(modifier = Modifier.fillMaxWidth()) {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(cfg.name, style = MaterialTheme.typography.titleMedium)
                            Text("${cfg.baseUrl} · " + tr("модель", "model") + ": ${cfg.model}",
                                style = MaterialTheme.typography.bodySmall)
                            Text(
                                text = if (activeId == cfg.id) tr("Основной AI", "Main AI") else stringResource(R.string.provider_status),
                                style = MaterialTheme.typography.labelLarge
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ActionChip(tr("Настроить", "Set up")) {
                            editing = cfg
                        }
                        ActionChip(stringResource(R.string.provider_test)) {
                            scope.launch(Dispatchers.IO) {
                                val r = manager.build(cfg).testConnection()
                                withContext(Dispatchers.Main) {
                                    Toast.makeText(context, if (r.success) connOk else "$errorWord: ${r.text.take(80)}",
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
                            // Если удалили активный провайдер — сбрасываем
                            // выбор, иначе роутер будет искать несуществующий.
                            if (activeId == cfg.id) {
                                activeId = null
                                scope.launch { settings.setActiveProvider(null) }
                            }
                            configs = manager.list()
                        }
                    }
                }
            }
        }
        item {
            Text(tr("Добавить провайдера из шаблонов", "Add a provider from templates"), style = MaterialTheme.typography.titleMedium,
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
                else tr(
                    "Внешние AI не обязательны: launcher, Hands и голос работают и без них.",
                    "External AI is optional: the launcher, Hands and voice work without it."
                ),
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
 *
 * Загрузка идёт в [com.svetlana.home.ai.ModelDownloadService] — если свернуть окно,
 * модель докачивается, а прогресс виден в уведомлении и здесь.
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
    val downloads by ModelDownloads.states.collectAsState()
    val reports = remember { compat.compatibleModels(registry) }
    // ТЗ §35/аудит п.2: автоматическая проверка модели после установки.
    var verification by remember { mutableStateOf<ModelVerificationRunner.Report?>(null) }
    var verifying by remember { mutableStateOf<String?>(null) }
    val mb = tr("МБ", "MB")

    LaunchedEffect(Unit) { activeModelId = settings.activeLocalModelId.first() }
    // Загрузка завершилась (в том числе пока экран был закрыт) — обновить список.
    LaunchedEffect(downloads) { installed = manager.list() }

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
            item { Text(tr("Список пуст — это нормальное состояние.", "The list is empty — that’s normal."), style = MaterialTheme.typography.bodySmall) }
        }
        items(installed.size) { i ->
            val model = installed[i]
            val isActive = model.modelId == activeModelId
            val status = remember(model.modelId, isActive) {
                manager.statusFor(
                    modelId = model.modelId,
                    runtimeReady = ServiceLocator.compositeRuntime.isReadyFor(model.modelId),
                    // A runtime being usable for a model does not prove that
                    // the model is currently resident in memory.
                    modelLoaded = false,
                    inferenceVerified = model.benchmark?.status == SvetlanaStatus.DEVICE_VERIFIED
                )
            }
            GlassCard {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(model.name, style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.weight(1f))
                        if (isActive) {
                            Text("● " + tr("Используется", "In use"), style = MaterialTheme.typography.labelLarge,
                                color = MintPrimary)
                        }
                    }
                    Text("${model.sizeBytes / (1024 * 1024)} $mb",
                        style = MaterialTheme.typography.bodySmall)
                    // Аудит §14: строгий жизненный цикл вместо оптимистичного isAvailable()
                    Text(tr("Статус", "Status") + ": ${trLabel(status.label)}", style = MaterialTheme.typography.labelLarge)
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        // Аудит §15: явный выбор основной модели
                        if (!isActive) {
                            ActionChip(tr("Сделать основной", "Make main")) {
                                scope.launch(Dispatchers.IO) {
                                    settings.setActiveLocalModel(model.modelId)
                                    activeModelId = model.modelId
                                    ServiceLocator.historyManager.record(HistoryCategory.MODELS,
                                        "Основная модель: ${model.name}")
                                }
                            }
                        }
                        ActionChip(stringResource(R.string.model_benchmark)) {
                            scope.launch(Dispatchers.IO) {
                                val result = BenchmarkRunner.run(
                                    registry.byId(model.modelId)!!, manager, ServiceLocator.compositeRuntime)
                                manager.recordBenchmark(result)
                                installed = manager.list()
                                ServiceLocator.historyManager.record(HistoryCategory.MODELS,
                                    "Benchmark ${model.name}: ${result.tokensPerSecond} ток/с, ${result.status}")
                            }
                        }
                        // Аудит п.2: «Проверить модель» — реальная цепочка
                        ActionChip(tr("Проверить модель", "Test model")) {
                            verifying = model.modelId
                            scope.launch(Dispatchers.IO) {
                                val report = ModelVerificationRunner.verify(
                                    model.modelId, manager, registry, ServiceLocator.compositeRuntime,
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
                        // Аудит §15: активную модель удалять запрещено
                        if (!isActive) {
                            ActionChip(stringResource(R.string.model_delete)) {
                                manager.uninstall(model.modelId)
                                installed = manager.list()
                                verification = null
                            }
                        }
                    }
                    // Результат проверки модели (аудит п.2)
                    if (verifying == model.modelId) {
                        Spacer(Modifier.height(8.dp))
                        Text(tr("Проверяю модель…", "Testing model…"), style = MaterialTheme.typography.bodySmall)
                    }
                    verification?.takeIf { it.modelId == model.modelId }?.let { rep ->
                        Spacer(Modifier.height(8.dp))
                        Text(rep.summary(), style = MaterialTheme.typography.bodySmall)
                    }
                    // Итог фоновой загрузки с автопроверкой (аудит п.2)
                    (downloads[model.modelId] as? ModelDownloads.State.Finished)?.takeIf { it.success }?.let { fin ->
                        Spacer(Modifier.height(8.dp))
                        Text(fin.message, style = MaterialTheme.typography.bodySmall)
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
            val dl = downloads[report.model.id]
            GlassCard {
                Column {
                    Text(report.model.name, style = MaterialTheme.typography.titleMedium)
                    Text("${stringResource(R.string.model_size)}: ${report.model.sizeMb} $mb · " +
                            "${stringResource(R.string.model_ram_required)}: ${report.model.ramRequirementMb} $mb",
                        style = MaterialTheme.typography.bodySmall)
                    // ТЗ §33: всегда показываем свободное место — пользователь
                    // видит, займёт ли модель слишком много storage.
                    Text("${stringResource(R.string.model_free_space)}: ${manager.freeSpaceBytes() / (1024 * 1024)} $mb",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (report.model.sizeMb > manager.freeSpaceBytes() / (1024 * 1024))
                            WarnAmber else TextTertiary)
                    Text(report.reasons.joinToString("\n"), style = MaterialTheme.typography.bodySmall)
                    Text("${stringResource(R.string.model_perf)}: ${report.expectedPerf}",
                        style = MaterialTheme.typography.bodySmall)
                    Text(tr("Совместимость", "Compatibility") + ": ${report.level.label}", style = MaterialTheme.typography.labelLarge)
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (dl is ModelDownloads.State.Running) {
                            Text(tr("Загрузка…", "Downloading…") + " ${dl.percent}% · ${report.model.sizeMb} $mb",
                                style = MaterialTheme.typography.bodySmall)
                        } else if (!alreadyInstalled) {
                            ActionChip(stringResource(R.string.model_download)) {
                                // Только по решению пользователя (ТЗ §32/§87).
                                // Фоновый сервис: не обрывается, если свернуть окно.
                                ModelDownloads.start(context.applicationContext, report.model.id)
                            }
                            ActionChip(stringResource(R.string.model_details)) { }
                        } else {
                            Text(tr("Установлена", "Installed"), style = MaterialTheme.typography.labelLarge)
                        }
                        ActionChip(stringResource(R.string.model_not_now)) { }
                    }
                    if (dl is ModelDownloads.State.Running) {
                        Spacer(Modifier.height(4.dp))
                        Text(tr("Можно свернуть окно — загрузка продолжится в фоне.",
                            "You can leave this screen — the download continues in the background."),
                            style = MaterialTheme.typography.bodySmall, color = TextTertiary)
                    }
                    if (dl is ModelDownloads.State.Finished && !dl.success && !alreadyInstalled) {
                        Spacer(Modifier.height(4.dp))
                        Text(tr("Ошибка загрузки", "Download failed") + ": ${dl.message}",
                            style = MaterialTheme.typography.bodySmall, color = WarnAmber)
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
                    Text(tr("Контекст", "Context") + ": ${if (r.model.context > 0) r.model.context else "—"} · " +
                            tr("Лицензия", "License") + ": ${r.model.license}",
                        style = MaterialTheme.typography.bodySmall)
                    Text(tr("Источник", "Source") + ": ${r.model.source}", style = MaterialTheme.typography.bodySmall)
                    Text(tr("Совместимость", "Compatibility") + ": ${r.level.label}", style = MaterialTheme.typography.labelLarge)
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
    val serverUp = tr("Сервер доступен", "Server is reachable")
    val serverDown = tr("Сервер недоступен", "Server is unreachable")
    val inferenceFailed = tr(
        "✕ Inference не выполнен — сервер недоступен или не поддерживает его",
        "✕ Inference failed — the server is unreachable or doesn’t support it"
    )
    val replyWord = tr("Ответ", "Reply")
    val msWord = tr("мс", "ms")
    val gb = tr("ГБ", "GB")

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
                        label = { Text(tr("Токен доступа", "Access token")) },
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ActionChip(tr("Сохранить", "Save")) {
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
                                        if (ok) serverUp else serverDown,
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
                    ActionChip(tr("Проверить inference", "Test inference")) {
                        scope.launch(Dispatchers.IO) {
                            val started = System.currentTimeMillis()
                            val reply = manager.inference("Ответь одним словом: работает.")
                            val latency = System.currentTimeMillis() - started
                            withContext(Dispatchers.Main) {
                                inferenceStatus = if (reply.isNullOrBlank()) {
                                    inferenceFailed
                                } else {
                                    "✓ $replyWord: «${reply.take(40)}» (${latency}$msWord)"
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
                    Text("RAM: ${caps.ramGb} $gb", style = MaterialTheme.typography.bodySmall)
                    Text("GPU: ${caps.gpu}", style = MaterialTheme.typography.bodySmall)
                    Text("VRAM: ${caps.vramGb} $gb", style = MaterialTheme.typography.bodySmall)
                    Text("Inference: " + if (caps.inferenceSupported) tr("да", "yes") else tr("нет", "no"),
                        style = MaterialTheme.typography.bodySmall)
                    Text(tr("Модели", "Models") + ": ${caps.models.joinToString(", ").ifBlank { "—" }}",
                        style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = tr(
                            "Удалённая RAM/VRAM не являются физической RAM телефона — " +
                                "это режим «Удалённая мощность».",
                            "Remote RAM/VRAM is not the phone’s physical RAM — " +
                                "this is the “Remote power” mode."
                        ),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
    }
}
