package com.svetlana.home.ai

import android.content.Context
import com.svetlana.home.ai.providers.OpenAiCompatibleProvider
import com.svetlana.home.ai.providers.PersonalServerProvider
import com.svetlana.home.memory.HistoryCategory
import com.svetlana.home.memory.HistoryManager
import com.svetlana.home.store.SettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.first
import com.svetlana.home.store.SecureKeyStore
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File

/**
 * ProviderManager — хранит конфигурации провайдеров и создаёт их реализации.
 */
class ProviderManager(
    private val context: Context,
    private val serverManager: com.svetlana.home.server.PersonalServerManager,
    private val localProvider: com.svetlana.home.ai.providers.LocalAIProvider
) {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    private val storeFile: File by lazy { File(context.filesDir, "providers.json") }
    private val keyStore = SecureKeyStore.create(context)

    @Volatile
    private var configs: List<ProviderConfig> = load()

    fun list(): List<ProviderConfig> = configs

    fun byId(id: String): ProviderConfig? = configs.firstOrNull { it.id == id }

    fun add(config: ProviderConfig): Boolean {
        if (configs.any { it.id == config.id }) return false
        configs = configs + config
        save()
        return true
    }

    fun update(config: ProviderConfig) {
        configs = configs.map { if (it.id == config.id) config else it }
        save()
    }

    fun remove(id: String) {
        keyStore.remove(SecureKeyStore.PROVIDER_KEY_PREFIX + id)
        configs = configs.filterNot { it.id == id }
        save()
    }

    fun setApiKey(id: String, key: String) =
        keyStore.put(SecureKeyStore.PROVIDER_KEY_PREFIX + id, key)

    fun apiKeyFor(id: String): String? = keyStore.get(SecureKeyStore.PROVIDER_KEY_PREFIX + id)

    /**
     * Создать реализацию провайдера по конфигурации.
     */
    fun build(config: ProviderConfig): AIProvider = when (config.type) {
        AIProvider.ProviderType.OPENAI_COMPATIBLE,
        AIProvider.ProviderType.ANTHROPIC_COMPATIBLE,
        AIProvider.ProviderType.CUSTOM -> OpenAiCompatibleProvider(context, config)
        AIProvider.ProviderType.PERSONAL_SERVER -> PersonalServerProvider(serverManager)
        AIProvider.ProviderType.LOCAL -> localProvider
    }

    private fun load(): List<ProviderConfig> = try {
        if (storeFile.exists() && storeFile.length() > 0)
            json.decodeFromString(ListSerializer(ProviderConfig.serializer()), storeFile.readText())
        else emptyList()
    } catch (t: Throwable) { emptyList() }

    private fun save() {
        try { storeFile.writeText(json.encodeToString(ListSerializer(ProviderConfig.serializer()), configs)) } catch (t: Throwable) { /* ignore */ }
    }
}

/**
 * Порция стримящегося ответа.
 *
 * [delta] — новый кусок текста (для провайдеров со стримингом),
 * [full] — весь накопленный текст на данный момент,
 * [done] — генерация завершена,
 * [success] — завершилась ли успешно.
 */
data class StreamChunk(
    val delta: String,
    val full: String,
    val done: Boolean,
    val success: Boolean,
    val backend: AIBackend
)

/**
 * AIRouter — выбирает провайдера и выполняет запрос (ТЗ §40).
 *
 * ВАЖНО (ТЗ §41): если пользователь выбрал «только локальный ИИ»,
 * Светлана не переключается на облако из-за ошибки. Она сообщает, что
 * локальная модель не справилась, и предлагает варианты.
 */
class AIRouter(
    private val context: Context,
    private val settings: SettingsRepository,
    private val modelRouter: ModelRouter,
    private val privacyRouter: PrivacyRouter,
    private val serverManager: com.svetlana.home.server.PersonalServerManager,
    private val historyManager: HistoryManager,
    private val providerManager: ProviderManager,
    private val localProvider: com.svetlana.home.ai.providers.LocalAIProvider,
    private val hybridPipeline: HybridPipeline? = null
) {

    suspend fun activeMode(): AIMode = settings.aiMode.first()

    /**
     * Какой ИИ сейчас отвечает (ТЗ §48).
     */
    suspend fun currentBackend(): AIBackend {
        val mode = activeMode()
        return when (mode) {
            AIMode.LOCAL_ONLY -> AIBackend.LOCAL
            AIMode.MY_SERVER -> if (serverManager.isReachable()) AIBackend.PERSONAL_SERVER else AIBackend.LOCAL
            AIMode.EXTERNAL -> {
                val id = settings.activeProviderId.first()
                if (id != null && providerManager.byId(id) != null) AIBackend.EXTERNAL
                else AIBackend.NONE
            }
            AIMode.LOCAL_FIRST -> AIBackend.LOCAL
            AIMode.AUTO -> AIBackend.LOCAL
        }
    }

    suspend fun currentBackendLabel(): String = currentBackend().humanReadable

    /**
     * Результат маршрутизации запроса. Вся логика выбора бэкенда и
     * privacy-проверок вынесена сюда, чтобы [chat] и [chatStream]
     * принимали одинаковые решения.
     */
    private sealed class Routing {
        abstract val backend: AIBackend
        data class Blocked(override val backend: AIBackend, val reason: String) : Routing()
        data class HybridRoute(
            override val backend: AIBackend,
            val pipeline: HybridPipeline,
            val mode: AIMode,
            val remoteBackend: AIBackend
        ) : Routing()
        data class Ready(override val backend: AIBackend, val provider: AIProvider) : Routing()
    }

    private suspend fun resolve(
        complexity: ModelRouter.TaskComplexity,
        dataType: PrivacyDataType = PrivacyDataType.TEXT
    ): Routing {
        val mode = activeMode()
        val routing = modelRouter.route(complexity, dataType)
        val privacy = privacyRouter.canSend(dataType, routing.backend, mode)

        if (!privacy.allowed && routing.backend != AIBackend.LOCAL) {
            historyManager.record(HistoryCategory.AI,
                "Запрос заблокирован PrivacyRouter: ${privacy.reason}")
            return Routing.Blocked(routing.backend, privacy.reason)
        }

        // ТЗ §46: HYBRID — реальный pipeline.
        if (routing.backend == AIBackend.HYBRID) {
            val pipeline = hybridPipeline
            if (pipeline == null) {
                historyManager.record(HistoryCategory.AI, "HYBRID выбран, pipeline не подключён")
                return Routing.Blocked(routing.backend, "Гибридный режим недоступен в этой сборке")
            }
            return Routing.HybridRoute(routing.backend, pipeline, mode, routing.remoteBackend)
        }

        val provider = when (routing.backend) {
            AIBackend.LOCAL -> localProvider
            AIBackend.PERSONAL_SERVER -> providerManager.build(
                ProviderConfig(id = "personal-server", name = "Мой сервер", type = AIProvider.ProviderType.PERSONAL_SERVER)
            )
            AIBackend.EXTERNAL -> {
                val id = settings.activeProviderId.first()
                val cfg = id?.let { providerManager.byId(it) }
                if (cfg != null) providerManager.build(cfg) else null
            }
            AIBackend.NONE -> null
            AIBackend.HYBRID -> localProvider // обработано выше; сюда не доходим
        }

        // ТЗ §51: внешние провайдеры опциональны. Если выбран внешний ИИ,
        // но провайдер не настроен — честно говорим, как его подключить,
        // а не подменяем тихо другим бэкендом.
        if (provider == null) {
            val msg = when (routing.backend) {
                AIBackend.EXTERNAL -> "Внешний ИИ не настроен. Подключите провайдера в «Настройки» → «Внешние ИИ» — и я смогу отвечать на свободные вопросы."
                AIBackend.NONE -> "Сейчас не выбран ни один ИИ. Локальную модель можно установить в «Локальный ИИ», либо подключить внешний провайдер."
                else -> "Выбранный ИИ недоступен."
            }
            historyManager.record(HistoryCategory.AI,
                "chat: провайдер не настроен (backend=${routing.backend}, mode=$mode)")
            return Routing.Blocked(routing.backend, msg)
        }

        if (!provider.isAvailable() && provider is com.svetlana.home.ai.providers.LocalAIProvider && mode == AIMode.LOCAL_ONLY) {
            // ТЗ §41: не уходим в облако при LOCAL_ONLY
            val msg = "Локальная модель не может выполнить эту задачу. Варианты: попробовать другую локальную модель, использовать мой сервер, использовать внешний AI, отмена."
            historyManager.record(HistoryCategory.AI, "Локальный ИИ не справился, режим LOCAL_ONLY удержан")
            return Routing.Blocked(AIBackend.LOCAL, msg)
        }

        return Routing.Ready(routing.backend, provider)
    }

    /**
     * Основной цикл диалога.
     */
    suspend fun chat(
        prompt: String,
        complexity: ModelRouter.TaskComplexity = ModelRouter.TaskComplexity.LIGHT
    ): AIResult {
        val started = System.currentTimeMillis()
        return when (val r = resolve(complexity)) {
            is Routing.Blocked -> AIResult(false, r.reason, r.backend, latencyMs = System.currentTimeMillis() - started)
            is Routing.HybridRoute -> {
                val hybridResult = r.pipeline.run(
                    prompt = prompt,
                    dataType = PrivacyDataType.TEXT,
                    mode = r.mode,
                    remoteBackend = r.remoteBackend
                )
                historyManager.record(HistoryCategory.AI,
                    "hybrid → ${hybridResult.backend} ${if (hybridResult.success) "OK" else "FAIL"} " +
                    "(${hybridResult.latencyMs}мс, этапов: ${hybridResult.stages.size})")
                AIResult(
                    success = hybridResult.success,
                    text = hybridResult.text,
                    backend = hybridResult.backend,
                    latencyMs = hybridResult.latencyMs
                )
            }
            is Routing.Ready -> {
                val result = r.provider.chat(prompt)
                historyManager.record(HistoryCategory.AI,
                    "chat → ${result.backend} ${if (result.success) "OK" else "FAIL"} (${result.latencyMs}мс)")
                result
            }
        }
    }

    /**
     * Стриминговый диалог: ответ течёт по мере генерации.
     *
     * Требование: «голос должен начинать говорить сразу, даже пока ответ
     * полностью не получен». Провайдеры с [AIProvider.supportsStreaming]
     * отдают дельты; остальные — весь ответ одним куском в конце (а
     * VoiceAgent компенсирует это filler-фразой «Дай подумать…»).
     *
     * Маршрутизация и privacy-проверки идентичны [chat] — используется
     * общий [resolve], поэтому данные не могут уйти не туда.
     */
    fun chatStream(
        prompt: String,
        complexity: ModelRouter.TaskComplexity = ModelRouter.TaskComplexity.LIGHT
    ): Flow<StreamChunk> = flow {
        when (val r = resolve(complexity)) {
            is Routing.Blocked -> emit(StreamChunk(r.reason, r.reason, done = true, success = false, r.backend))
            is Routing.HybridRoute -> {
                val res = r.pipeline.run(
                    prompt = prompt,
                    dataType = PrivacyDataType.TEXT,
                    mode = r.mode,
                    remoteBackend = r.remoteBackend
                )
                historyManager.record(HistoryCategory.AI,
                    "hybrid(stream) → ${res.backend} ${if (res.success) "OK" else "FAIL"} (${res.latencyMs}мс)")
                emit(StreamChunk(res.text, res.text, done = true, success = res.success, res.backend))
            }
            is Routing.Ready -> {
                if (r.provider.supportsStreaming()) {
                    val sb = StringBuilder()
                    try {
                        r.provider.chatStream(prompt).collect { delta ->
                            sb.append(delta)
                            emit(StreamChunk(delta, sb.toString(), done = false, success = true, r.backend))
                        }
                    } catch (t: Throwable) {
                        historyManager.record(HistoryCategory.AI, "stream прерван: ${t.message}")
                    }
                    val full = sb.toString()
                    if (full.isBlank()) {
                        // Стриминг ничего не отдал — честный фолбэк на обычный chat.
                        val fallback = r.provider.chat(prompt)
                        emit(StreamChunk(fallback.text, fallback.text, done = true, success = fallback.success, r.backend))
                    } else {
                        historyManager.record(HistoryCategory.AI,
                            "stream → ${r.backend} OK (${full.length} символов)")
                        emit(StreamChunk("", full, done = true, success = true, r.backend))
                    }
                } else {
                    val res = r.provider.chat(prompt)
                    historyManager.record(HistoryCategory.AI,
                        "chat → ${res.backend} ${if (res.success) "OK" else "FAIL"} (${res.latencyMs}мс)")
                    emit(StreamChunk(res.text, res.text, done = true, success = res.success, r.backend))
                }
            }
        }
    }

    /**
     * Multimodal-запрос: текст + изображение (аудит §9, P0).
     *
     * Изображение кодируется в JPEG и передаётся провайдеру через
     * [AIProvider.vision]. Маршрутизация и privacy-проверки — те же,
     * что и для [chat], но тип данных IMAGE: PrivacyPolicy запрещает
     * отправку картинок наружу в режимах LOCAL_FIRST/AUTO, поэтому
     * silently на внешний провайдер они не уйдут.
     *
     * Важно: вызов text-only chat() вместо vision() — это баг, который
     * и был главной причиной BLOCKED статуса Vision. Теперь изображение
     * реально доходит до модели.
     */
    suspend fun vision(
        prompt: String,
        imageBytes: ByteArray,
        complexity: ModelRouter.TaskComplexity = ModelRouter.TaskComplexity.HEAVY
    ): AIResult {
        val started = System.currentTimeMillis()
        return when (val r = resolve(complexity, PrivacyDataType.IMAGE)) {
            is Routing.Blocked -> AIResult(false, r.reason, r.backend,
                latencyMs = System.currentTimeMillis() - started)
            is Routing.HybridRoute -> {
                val res = r.pipeline.run(
                    prompt = prompt,
                    dataType = PrivacyDataType.IMAGE,
                    mode = r.mode,
                    remoteBackend = r.remoteBackend
                )
                historyManager.record(HistoryCategory.VISION,
                    "hybrid vision → ${res.backend} ${if (res.success) "OK" else "FAIL"} (${res.latencyMs}мс)")
                AIResult(res.success, res.text, res.backend, latencyMs = res.latencyMs)
            }
            is Routing.Ready -> {
                // Local LiteRT-LM capability is discovered when the model is first
                // loaded, so a cold local VLM must reach provider.vision().
                if (r.provider !is com.svetlana.home.ai.providers.LocalAIProvider &&
                    !r.provider.capabilities().vision) {
                    val msg = "Выбранный ИИ (${r.provider.displayName}) не поддерживает изображения. " +
                        "Подключите vision-модель в настройках или включите локальную VLM."
                    historyManager.record(HistoryCategory.VISION,
                        "vision отклонён: ${r.provider.displayName} без поддержки изображений")
                    return AIResult(false, msg, r.backend,
                        latencyMs = System.currentTimeMillis() - started)
                }
                val result = r.provider.vision(prompt, imageBytes)
                historyManager.record(HistoryCategory.VISION,
                    "vision → ${result.backend} ${if (result.success) "OK" else "FAIL"} " +
                        "(${imageBytes.size} байт, ${result.latencyMs}мс)")
                result
            }
        }
    }

    /**
     * Перечень доступных вариантов, когда локальный ИИ не справился (ТЗ §41).
     */
    fun fallbackOptions(): List<FallbackOption> = listOf(
        FallbackOption("Попробовать другую локальную модель", AIBackend.LOCAL),
        FallbackOption("Использовать мой сервер", AIBackend.PERSONAL_SERVER),
        FallbackOption("Использовать внешний AI", AIBackend.EXTERNAL),
        FallbackOption("Отмена", AIBackend.NONE)
    )

    data class FallbackOption(val title: String, val backend: AIBackend)
}
