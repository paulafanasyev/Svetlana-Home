package com.svetlana.home.core

import android.app.Application
import android.content.Context
import com.svetlana.home.ai.AIRouter
import com.svetlana.home.ai.AIModelCompatibilityEngine
import com.svetlana.home.ai.AIModelRegistry
import com.svetlana.home.ai.HybridPipeline
import com.svetlana.home.ai.LocalModelManager
import com.svetlana.home.ai.ModelRouter
import com.svetlana.home.ai.PrivacyRouter
import com.svetlana.home.ai.ProviderConfig
import com.svetlana.home.ai.ProviderManager
import com.svetlana.home.ai.AIProvider
import com.svetlana.home.ai.providers.LocalAIProvider
import com.svetlana.home.ai.local.LlamaCppRuntime
import kotlinx.coroutines.flow.first
import com.svetlana.home.apps.AppRegistry
import com.svetlana.home.apps.AppRepository
import com.svetlana.home.control.ActionRouter
import com.svetlana.home.control.AppControlEngine
import com.svetlana.home.control.IntentResolver
import com.svetlana.home.device.DeviceCapabilityManager
import com.svetlana.home.hands.HandsController
import com.svetlana.home.harness.MobileHarnessController
import com.svetlana.home.memory.HistoryManager
import com.svetlana.home.memory.PersonalMemory
import com.svetlana.home.owner.OwnerIdentity
import com.svetlana.home.permissions.PermissionManager
import com.svetlana.home.server.PersonalServerManager
import com.svetlana.home.store.SettingsRepository
import com.svetlana.home.translate.SvetlanaTranslator
import com.svetlana.home.translate.TranslatorProviderManager
import com.svetlana.home.avatar.AvatarEngine
import com.svetlana.home.avatar.AvatarLevel
import com.svetlana.home.avatar.AvatarRendererRegistry
import com.svetlana.home.vision.VisionManager
import com.svetlana.home.voice.SvetlanaSpeechRecognizer
import com.svetlana.home.voice.SvetlanaTts
import com.svetlana.home.voice.WakeWordEngine

/**
 * Лёгкий локатор зависимостей: единое место, где собираются подсистемы Светланы.
 * Никаких скрытых сервисов: каждая подсистема явная и проверяемая.
 */
object ServiceLocator {

    private lateinit var app: Application

    val settings by lazy { SettingsRepository(app) }
    val device by lazy { DeviceCapabilityManager(app) }
    val permissionManager by lazy { PermissionManager(app) }
    val ownerIdentity by lazy { OwnerIdentity(app) }
    val appRepository by lazy { AppRepository(app) }
    val appRegistry by lazy { AppRegistry(app, appRepository) }
    val intentResolver by lazy { IntentResolver(app, appRegistry) }
    val hands by lazy { HandsController(app) }
    val harness by lazy { MobileHarnessController(app, appRegistry, hands) }
    val controlEngine by lazy { AppControlEngine(app, intentResolver, hands, permissionManager) }
    val actionRouter by lazy {
        ActionRouter(app, controlEngine, intentResolver, permissionManager, historyManager)
    }
    val tts by lazy { SvetlanaTts(app) }
    val speechRecognizer by lazy { SvetlanaSpeechRecognizer(app) }
    val wakeWord by lazy { WakeWordEngine(app, speechRecognizer, settings) }
    val modelRegistry by lazy { AIModelRegistry() }
    val localModelManager by lazy { LocalModelManager(app) }
    val llamaRuntime by lazy { LlamaCppRuntime(app, localModelManager, modelRegistry) }
    val localAiProvider by lazy { LocalAIProvider(localModelManager, modelRegistry, llamaRuntime) }
    val compatibility by lazy { AIModelCompatibilityEngine(device) }
    val serverManager by lazy { PersonalServerManager(app, settings) }
    val providerManager by lazy { ProviderManager(app, serverManager, localAiProvider) }
    val privacyRouter by lazy { PrivacyRouter(settings) }
    val modelRouter by lazy { ModelRouter(device, settings, serverManager, privacyRouter) }
    val historyManager by lazy { HistoryManager(app) }
    val hybridPipeline by lazy {
        // ТЗ §46: реальный гибридный pipeline. Remote-провайдер выбирается
        // маршрутизатором (personal server или внешний провайдер).
        HybridPipeline(
            localProvider = localAiProvider,
            remoteProviderFactory = {
                // suspend лямбда: читаем активный провайдер; если внешний не
                // выбран — гибрид идёт на personal server.
                val activeId = settings.activeProviderId.first()
                val cfg = activeId?.let { providerManager.byId(it) }
                    ?: ProviderConfig(id = "personal-server", name = "Мой сервер",
                        type = AIProvider.ProviderType.PERSONAL_SERVER)
                providerManager.build(cfg)
            },
            recordHistory = { category, msg -> historyManager.record(category, msg) }
        )
    }
    val aiRouter by lazy {
        AIRouter(app, settings, modelRouter, privacyRouter, serverManager, historyManager,
            providerManager, localAiProvider, hybridPipeline)
    }
    val personalMemory by lazy { PersonalMemory(app, settings) }
    val translatorProvider by lazy { TranslatorProviderManager(app, aiRouter) }
    val translator by lazy { SvetlanaTranslator(app, translatorProvider, tts, speechRecognizer) }
    val vision by lazy { VisionManager(app, hands, translatorProvider) }
    val avatarEngine by lazy {
        // Регистрация renderer'ов, которые реально есть в этой сборке.
        // L0 (Orb) и L1 (Light Avatar на Compose) — точно присутствуют.
        // L2/L3 не регистрируются, пока не появится соответствующий движок,
        // чтобы AvatarEngine не мог заявить нереализованную возможность.
        AvatarRendererRegistry.reset()
        AvatarRendererRegistry.register(AvatarLevel.L1_LIGHT_AVATAR)
        AvatarEngine(device, serverManager)
    }

    val permissionCenter by lazy { permissionManager }

    fun init(application: Application) {
        app = application
        // Повторная инициализация подсистем, которым важен контекст приложения
        device.refresh()
    }

    fun onTrimMemory(level: Int) {
        if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_MODERATE) {
            hands.onLowMemory()
        }
    }

    @Suppress("unused")
    fun context(): Context = app
}
