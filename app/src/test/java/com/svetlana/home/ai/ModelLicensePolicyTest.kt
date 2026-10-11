package com.svetlana.home.ai

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Коммерческий продукт: некоммерческие и неясные лицензии никогда не скачиваются. */
class ModelLicensePolicyTest {
    private val registry = AIModelRegistry()

    @Test
    fun nonCommercialModelsAreNotInstallable() {
        registry.all()
            .filter { m -> listOf("NC", "Research", "неизвестна").any { m.license.contains(it) } }
            .forEach { m -> assertThat(m.runtimeImplemented).isFalse() }
        listOf("qwen2.5-3b-instruct-q4", "aya-8b-q4", "piper-ru-irina-tts").forEach { id ->
            assertThat(registry.byId(id)!!.runtimeImplemented).isFalse()
        }
    }

    @Test
    fun agreedPhoneDefaultIsPinnedAndVerifiable() {
        val m = registry.byId("qwen3-0.6b-litert")!!
        assertThat(m.runtimeImplemented).isTrue()
        assertThat(m.license).isEqualTo("Apache 2.0")
        assertThat(m.expectedSha256).isEqualTo("555579ff2f4fd13379abe69c1c3ab5200f7338bc92471557f1d6614a6e5ab0b4")
        assertThat(m.downloadUrl).matches(".*/resolve/[0-9a-f]{40}/.+\\.litertlm$")
        assertThat(ModelSourcePolicy.isAllowedUrl(m.downloadUrl)).isTrue()
        assertThat(registry.llmModels().map { it.id }).contains(m.id)
    }
}
