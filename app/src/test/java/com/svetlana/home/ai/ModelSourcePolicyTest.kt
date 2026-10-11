package com.svetlana.home.ai

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ModelSourcePolicyTest {
    @Test
    fun onlyHuggingFaceOverHttps() {
        assertThat(ModelSourcePolicy.isAllowedUrl("https://huggingface.co/Qwen/x/resolve/abc/m.gguf")).isTrue()
        assertThat(ModelSourcePolicy.isAllowedUrl("https://cdn-lfs.hf.co/repos/x")).isTrue()
        assertThat(ModelSourcePolicy.isAllowedUrl("http://huggingface.co/x")).isFalse()
        assertThat(ModelSourcePolicy.isAllowedUrl("https://huggingface.co.evil.com/x")).isFalse()
        assertThat(ModelSourcePolicy.isAllowedUrl("https://evilhuggingface.co/x")).isFalse()
        assertThat(ModelSourcePolicy.isAllowedUrl("https://user@huggingface.co/x")).isFalse()
        assertThat(ModelSourcePolicy.isAllowedUrl("https://huggingface.co:8443/x")).isFalse()
        assertThat(ModelSourcePolicy.isAllowedUrl("not a url")).isFalse()
    }

    @Test
    fun everyDownloadableRegistryModelIsOnAnAllowedSource() {
        AIModelRegistry().all().filter { it.runtimeImplemented }.forEach { model ->
            assertThat(ModelSourcePolicy.isAllowedUrl(model.downloadUrl)).isTrue()
        }
    }

    @Test
    fun sizeCapIsAboveDeclaredSize() {
        assertThat(ModelSourcePolicy.maxBytes(1000, 1100)).isGreaterThan(1100L * 1024 * 1024)
    }
}
