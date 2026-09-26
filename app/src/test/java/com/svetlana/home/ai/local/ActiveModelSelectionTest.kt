package com.svetlana.home.ai.local

import com.google.common.truth.Truth.assertThat
import com.svetlana.home.ai.AIModelRegistry
import com.svetlana.home.ai.InstalledModel
import com.svetlana.home.ai.LocalModelManager
import com.svetlana.home.ai.providers.InferenceRuntime
import com.svetlana.home.ai.providers.LocalAIProvider
import kotlinx.coroutines.runBlocking
import org.junit.Test

/**
 * Аудит п.6: Local AI должен использовать модель, ВЫБРАННУЮ пользователем
 * («Сделать основной»), а не первую установленную. Раньше activeModel() брал
 * modelManager.list().firstOrNull() — и если установлены A и B, а пользователь
 * выбрал B, runtime грузил A. Эти тесты доказывают, что выбор пользователя
 * теперь учитывается на уровне поставщика.
 *
 * Логика выбора изолирована в LocalAIProvider и тестируется на чистой JVM
 * через ContextWrapper (не требует реального Android).
 */
class ActiveModelSelectionTest {

    private val registry = AIModelRegistry()

    /** Записывает, с каким modelId его звали — доказывает выбор runtime'ом. */
    private class RecordingRuntime : InferenceRuntime {
        val calledWith = mutableListOf<String>()
        var ready = true
        override fun isReady(): Boolean = ready
        override fun supportedModelIds(): List<String> = emptyList()
        override fun generate(modelId: String, prompt: String, maxTokens: Int): String {
            calledWith += modelId
            return "ответ"
        }
    }

    private class StubModelManager(
        private val installed: List<InstalledModel>
    ) : LocalModelManager(NoopContext) {
        override fun list(): List<InstalledModel> = installed
        override fun byId(modelId: String): InstalledModel? =
            installed.firstOrNull { it.modelId == modelId }
    }

    private val modelA = InstalledModel(
        modelId = "llama3.2-1b-instruct-q4", name = "Llama 3.2 1B",
        filePath = "/models/a.bin", sizeBytes = 700_000_000L, installedAt = 1000L
    )
    private val modelB = InstalledModel(
        modelId = "qwen2.5-1.5b-instruct-q4", name = "Qwen2.5 1.5B",
        filePath = "/models/b.bin", sizeBytes = 1_000_000_000L, installedAt = 2000L
    )

    private fun provider(installed: List<InstalledModel>, activeId: String?): LocalAIProvider =
        LocalAIProvider(
            modelManager = StubModelManager(installed),
            registry = registry,
            runtime = RecordingRuntime(),
            activeModelId = { activeId }
        )

    @Test
    fun selectedModel_isUsed_whenPresent() {
        // Пользователь выбрал B (вторая установленная) — провайдер должен
        // сообщать именно её, а не первую попавшуюся.
        val provider = provider(listOf(modelA, modelB), modelB.modelId)
        assertThat(provider.redactedConfig()).contains("Qwen2.5 1.5B")
    }

    @Test
    fun firstInstalled_used_whenNoSelection() {
        // Нет явного выбора — нормально брать первую установленную.
        val provider = provider(listOf(modelA, modelB), null)
        assertThat(provider.redactedConfig()).contains("Llama 3.2 1B")
    }

    @Test
    fun unknownSelection_fallsBackToFirstInstalled() {
        // Выбранная модель удалена — провайдер откатывается, не падая.
        val provider = provider(listOf(modelA, modelB), "deleted-id")
        assertThat(provider.isConfigured()).isTrue()
        assertThat(provider.redactedConfig()).contains("Llama 3.2 1B")
    }

    @Test
    fun noModels_providerReportsNotConfigured() {
        val provider = provider(emptyList(), null)
        assertThat(provider.isConfigured()).isFalse()
        // describeBackend не должен падать без моделей
        assertThat(provider.describeBackend()).contains("не установлена")
    }

    @Test
    fun selectedModel_usedForGeneration() = runBlocking {
        val rt = RecordingRuntime()
        val provider = LocalAIProvider(
            modelManager = StubModelManager(listOf(modelA, modelB)),
            registry = registry,
            runtime = rt,
            activeModelId = { modelB.modelId }
        )
        provider.chat("привет")
        // Ключевое доказательство: generate позвали с выбранной моделью B,
        // а не с первой установленной A.
        assertThat(rt.calledWith).containsExactly(modelB.modelId)
        Unit
    }
}

/**
 * Контекст-заглушка через ContextWrapper: методы LocalModelManager, требующие
 * реального Android (filesDir, загрузка файлов), не вызываются, потому что
 * мы переопределяем list()/byId().
 */
private object NoopContext : android.content.ContextWrapper(null)
