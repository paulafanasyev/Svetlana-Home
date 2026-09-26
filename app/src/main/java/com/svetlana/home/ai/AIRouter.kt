package com.svetlana.home.ai

import android.content.Context
import com.svetlana.home.ai.providers.OpenAiCompatibleProvider
import com.svetlana.home.ai.providers.PersonalServerProvider
import com.svetlana.home.memory.HistoryCategory
import com.svetlana.home.memory.HistoryManager
import com.svetlana.home.store.SettingsRepository
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
     * Основной цикл диалога.
     */
    suspend fun chat(prompt: String, complexity: ModelRouter.TaskComplexity = ModelRouter.TaskComplexity.LIGHT): AIResult {
        val mode = activeMode()
        val routing = modelRouter.route(complexity)
        val privacy = privacyRouter.canSend(PrivacyDataType.TEXT, routing.backend, mode)

        if (!privacy.allowed && routing.backend != AIBackend.LOCAL) {
            historyManager.record(HistoryCategory.AI,
                "Запрос заблокирован PrivacyRouter: ${privacy.reason}")
            return AIResult(false, privacy.reason, routing.backend)
        }

        // ТЗ §46: HYBRID — реальный pipeline: локальная предобработка,
        // санитизация, remote inference, локальная постобработка.
        // Если pipeline не подключён — честно сообщаем, что гибрид недоступен,
        // вместо тихого выполнения только на локальном провайдере.
        if (routing.backend == AIBackend.HYBRID) {
            val pipeline = hybridPipeline
            if (pipeline == null) {
                historyManager.record(HistoryCategory.AI, "HYBRID выбран, pipeline не подключён")
                return AIResult(false, "Гибридный режим недоступен в этой сборке", routing.backend)
            }
            val hybridResult = pipeline.run(prompt, PrivacyDataType.TEXT, mode = mode)
            historyManager.record(HistoryCategory.AI,
                "hybrid → ${hybridResult.backend} ${if (hybridResult.success) "OK" else "FAIL"} " +
                "(${hybridResult.latencyMs}мс, этапов: ${hybridResult.stages.size})")
            return AIResult(
                success = hybridResult.success,
                text = hybridResult.text,
                backend = hybridResult.backend,
                latencyMs = hybridResult.latencyMs
            )
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
                AIBackend.EXTERNAL -> "Внешний ИИ не настроен. Подключите провайдер в «Настройки» → «Внешние ИИ» — и я смогу отвечать на свободные вопросы."
                AIBackend.NONE -> "Сейчас не выбран ни один ИИ. Локальную модель можно установить в «Локальный ИИ», либо подключить внешний провайдер."
                else -> "Выбранный ИИ недоступен."
            }
            historyManager.record(HistoryCategory.AI,
                "chat: провайдер не настроен (backend=${routing.backend}, mode=$mode)")
            return AIResult(false, msg, routing.backend)
        }

        if (!provider.isAvailable() && provider is com.svetlana.home.ai.providers.LocalAIProvider && mode == AIMode.LOCAL_ONLY) {
            // ТЗ §41: не уходим в облако при LOCAL_ONLY
            val msg = "Локальная модель не может выполнить эту задачу. Варианты: попробовать другую локальную модель, использовать мой сервер, использовать внешний AI, отмена."
            historyManager.record(HistoryCategory.AI, "Локальный ИИ не справился, режим LOCAL_ONLY удержан")
            return AIResult(false, msg, AIBackend.LOCAL)
        }

        val result = provider.chat(prompt)
        historyManager.record(HistoryCategory.AI,
            "chat → ${result.backend} ${if (result.success) "OK" else "FAIL"} (${result.latencyMs}мс)")
        return result
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
