package com.svetlana.home.ai

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Докачка моделей: продолжаем только если сервер отдал именно запрошенный кусок. */
class ContentRangeTest {

    @Test
    fun parsesStartOfRange() {
        assertThat(LocalModelManager.contentRangeStart("bytes 1000-1999/5000")).isEqualTo(1000L)
        assertThat(LocalModelManager.contentRangeStart("Bytes 0-99/*")).isEqualTo(0L)
        assertThat(LocalModelManager.contentRangeStart(" bytes 524288000-1117320704/1117320705 ")).isEqualTo(524288000L)
    }

    @Test
    fun rejectsGarbage() {
        assertThat(LocalModelManager.contentRangeStart(null)).isNull()
        assertThat(LocalModelManager.contentRangeStart("")).isNull()
        assertThat(LocalModelManager.contentRangeStart("bytes */5000")).isNull()
        assertThat(LocalModelManager.contentRangeStart("items 1-2/3")).isNull()
    }
}
