package com.svetlana.home.ai

import com.google.common.truth.Truth.assertThat
import com.svetlana.home.memory.HistoryCategory
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest

import org.junit.Test

/**
 * Тесты гибридного pipeline (ТЗ §46, §47, аудит п.20).
 *
 * Главная цель: доказать, что HYBRID больше НЕ отправляет запрос просто в
 * локальный провайдер, а реализует цепочку
 *   privacy → preprocess → sanitize → remote → postprocess,
 * и что LOCAL_ONLY физически исключает передачу данных.
 *
 * Зависимости подаются функциональными лямбдами, а privacy-логика
 * вынесена в чистую функцию PrivacyPolicy — поэтому тест не зависит
 * от Android-классов (DataStore/Context) и выполняется на JVM.
 */
class HybridPipelineTest {

    @Test
    fun localOnly_blocksRemoteAndFallsBackToDevice() = runTest {
        val pipeline = HybridPipeline(
            localProvider = stubLocal("локальный ответ"),
            remoteProviderFactory = { error("LOCAL_ONLY не должен создавать remote-провайдер") },
            recordHistory = { _, _ -> }
        )

        val result = pipeline.run("переведи текст", PrivacyDataType.TEXT, mode = AIMode.LOCAL_ONLY)

        assertThat(result.success).isTrue()
        // Данные не ушли наружу — ответ пришёл с устройства.
        assertThat(result.backend).isEqualTo(AIBackend.LOCAL)
        assertThat(result.text).contains("локальный ответ")
        assertThat(result.stages.map { it.name }).contains("privacy")
        assertThat(result.stages.first { it.name == "privacy" }.ok).isFalse()
    }

    @Test
    fun myServer_runsFullHybridChain() = runTest {
        var remoteCalled = false
        val pipeline = HybridPipeline(
            localProvider = stubLocal("не должно использоваться"),
            remoteProviderFactory = { stubRemote { remoteCalled = true; "ответ сервера" } },
            recordHistory = { _, _ -> }
        )

        val result = pipeline.run("сложный запрос", PrivacyDataType.TEXT, mode = AIMode.MY_SERVER)

        assertThat(result.success).isTrue()
        assertThat(result.backend).isEqualTo(AIBackend.HYBRID)
        assertThat(result.text).isEqualTo("ответ сервера")
        assertThat(remoteCalled).isTrue()
        // Все этапы прошли в правильном порядке.
        assertThat(result.stages.map { it.name })
            .containsExactly("privacy", "preprocess", "remote", "postprocess").inOrder()
    }

    @Test
    fun remoteUnavailable_fallsBackToLocalHonestly() = runTest {
        val pipeline = HybridPipeline(
            localProvider = stubLocal("локальный fallback"),
            remoteProviderFactory = { null }, // remote недоступен
            recordHistory = { _, _ -> }
        )

        val result = pipeline.run("запрос", PrivacyDataType.TEXT, mode = AIMode.MY_SERVER)

        assertThat(result.success).isTrue()
        assertThat(result.backend).isEqualTo(AIBackend.LOCAL)
        assertThat(result.text).contains("локальный fallback")
        assertThat(result.stages.any { it.name == "remote" && !it.ok }).isTrue()
    }

    @Test
    fun remoteFailure_fallsBackToLocal() = runTest {
        val pipeline = HybridPipeline(
            localProvider = stubLocal("локальный fallback"),
            remoteProviderFactory = { stubRemoteFailing("сервер упал") },
            recordHistory = { _, _ -> }
        )

        val result = pipeline.run("запрос", PrivacyDataType.TEXT, mode = AIMode.MY_SERVER)

        assertThat(result.backend).isEqualTo(AIBackend.LOCAL)
        assertThat(result.text).contains("локальный fallback")
    }

    @Test
    fun sanitize_masksPinAndSecrets() = runTest {
        var captured: String? = null
        val pipeline = HybridPipeline(
            localProvider = stubLocal("локальный"),
            remoteProviderFactory = { stubRemote { captured = it; "ok" } },
            recordHistory = { _, _ -> }
        )

        pipeline.run("мой PIN 12345678, ключ sk-abcdef0123456789ABCDEF", PrivacyDataType.TEXT, mode = AIMode.MY_SERVER)

        // PIN и ключ не должны покинуть устройство
        assertThat(captured).isNotNull()
        assertThat(captured).doesNotContain("12345678")
        assertThat(captured).doesNotContain("sk-abcdef0123456789ABCDEF")
        assertThat(captured).contains("****")
    }

    @Test
    fun preprocess_trimsVeryLongInput() = runTest {
        var captured: String? = null
        val pipeline = HybridPipeline(
            localProvider = stubLocal("локальный"),
            remoteProviderFactory = { stubRemote { captured = it; "ok" } },
            recordHistory = { _, _ -> }
        )

        val long = "а".repeat(20000)
        pipeline.run(long, PrivacyDataType.TEXT, mode = AIMode.MY_SERVER)

        assertThat(captured).isNotNull()
        assertThat(captured!!.length).isLessThan(9000)
    }

    @Test
    fun screenshotUnderMyServer_isAllowed() = runTest {
        var remoteCalled = false
        val pipeline = HybridPipeline(
            localProvider = stubLocal("локальный"),
            remoteProviderFactory = { stubRemote { remoteCalled = true; "проанализировано" } },
            recordHistory = { _, _ -> }
        )

        val result = pipeline.run("что на экране", PrivacyDataType.SCREENSHOT, mode = AIMode.MY_SERVER)
        // MY_SERVER разрешает отправку скриншотов на свой сервер
        assertThat(result.stages.first { it.name == "privacy" }.ok).isTrue()
        assertThat(remoteCalled).isTrue()
    }

    @Test
    fun screenshotUnderLocalFirst_isBlocked() = runTest {
        // LOCAL_FIRST: тяжёлые данные остаются на устройстве
        val pipeline = HybridPipeline(
            localProvider = stubLocal("не нужен"),
            remoteProviderFactory = { error("не должно создаваться") },
            recordHistory = { _, _ -> }
        )

        val result = pipeline.run("что на экране", PrivacyDataType.SCREENSHOT, mode = AIMode.LOCAL_FIRST)
        assertThat(result.stages.first { it.name == "privacy" }.ok).isFalse()
        assertThat(result.backend).isEqualTo(AIBackend.LOCAL)
    }

    @Test
    fun historyRecordsBlockedHybrid() = runTest {
        val recorded = mutableListOf<Pair<HistoryCategory, String>>()
        val pipeline = HybridPipeline(
            localProvider = stubLocal("ответ"),
            remoteProviderFactory = { error("не должно создаваться") },
            recordHistory = { cat, msg -> recorded += cat to msg }
        )

        pipeline.run("запрос", PrivacyDataType.TEXT, mode = AIMode.LOCAL_ONLY)
        assertThat(recorded).isNotEmpty()
        assertThat(recorded.any { it.first == HistoryCategory.AI }).isTrue()
    }

    // ---------- helpers ----------

    private fun stubLocal(text: String) = object : AIProvider by StubProvider() {
        override suspend fun chat(prompt: String, systemPrompt: String?): AIResult =
            AIResult(true, text, AIBackend.LOCAL)
    }

    private fun stubRemote(onChat: (String) -> String) = object : AIProvider by StubProvider() {
        override val backend = AIBackend.PERSONAL_SERVER
        override suspend fun chat(prompt: String, systemPrompt: String?): AIResult {
            val out = onChat(prompt)
            return AIResult(true, out, AIBackend.PERSONAL_SERVER)
        }
    }

    private fun stubRemoteFailing(error: String) = object : AIProvider by StubProvider() {
        override val backend = AIBackend.PERSONAL_SERVER
        override suspend fun chat(prompt: String, systemPrompt: String?): AIResult =
            AIResult(false, "", AIBackend.PERSONAL_SERVER, error = error)
    }

    private open class StubProvider : AIProvider {
        override val id = "stub"
        override val displayName = "Stub"
        override val type = AIProvider.ProviderType.CUSTOM
        override val backend = AIBackend.LOCAL
        override fun capabilities() = ProviderCapabilities(true, false, false, false, 2048)
        override fun isConfigured() = true
        override fun isAvailable() = true
        override suspend fun chat(prompt: String, systemPrompt: String?) =
            AIResult(false, "stub", backend)
        override suspend fun vision(prompt: String, imageBytes: ByteArray) =
            AIResult(false, "stub", backend)
        override suspend fun testConnection() = AIResult(true, "stub", backend)
        override fun redactedConfig() = "stub://"
    }
}
