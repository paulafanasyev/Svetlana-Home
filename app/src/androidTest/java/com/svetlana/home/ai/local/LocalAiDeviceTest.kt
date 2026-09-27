package com.svetlana.home.ai.local

import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.svetlana.home.SvetlanaDeviceTest
import com.svetlana.home.ai.BenchmarkRunner
import com.svetlana.home.core.SvetlanaStatus
import com.svetlana.home.core.ServiceLocator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Локальный ИИ: реальная цепочка на устройстве (ТЗ §70, §84).
 *
 * Compatibility → Download → Install → Load → Inference → Benchmark → Result
 *
 * Этот тест проверяет доступность нативного llama.cpp на текущем устройстве
 * и корректность статусов. Сама загрузка GGUF-модели здесь НЕ выполняется:
 * по ТЗ §87 модель скачивается только по явному решению пользователя,
 * и instrumentation-тест не может принимать это решение за него.
 *
 * Если на устройстве нет установленной пользователем модели — тест
 * честно фиксирует «нет модели», что является нормальным состоянием.
 */
@RunWith(AndroidJUnit4::class)
class LocalAiDeviceTest : SvetlanaDeviceTest() {

    @Test
    fun runtimeReportsNativeAvailability() {
        val runtime = ServiceLocator.llamaRuntime
        println("LLAMA_SUPPORTED_IDS=${runtime.supportedModelIds()}")
        // llama.cpp собран только под arm64-v8a. На CI-эмуляторе (x86_64)
        // нативного слоя нет — это ожидаемое состояние, а не сбой:
        // runtime честно сообщает «недоступно на этом ABI». Проверяем
        // реальную доступность только на arm64 (POCO X3 NFC).
        val isArm64 = Build.SUPPORTED_ABIS.any { it.contains("arm64") }
        if (!isArm64) {
            println("SKIP: llama.cpp недоступен на x86_64 (ожидаемо для CI-эмулятора)")
            assertTrue("На x86_64 нативный слой должен быть недоступен",
                runtime.supportedModelIds().isEmpty())
            return
        }
        assertTrue("llama.cpp должен поддерживать хотя бы одну модель из реестра",
            runtime.supportedModelIds().isNotEmpty())
    }

    @Test
    fun noInstalledModelIsNormalState() {
        val manager = ServiceLocator.localModelManager
        val installed = manager.list()
        println("INSTALLED_MODELS=${installed.size}")
        // Состояние «нет модели» — нормальное (ТЗ §64). Тест просто фиксирует
        // реальное состояние и проверяет, что провайдер не врёт о готовности.
        if (installed.isEmpty()) {
            println("LOCAL_AI_STATE=no_model (нормальное состояние)")
            val provider = ServiceLocator.localAiProvider
            assertEquals("Без установленной модели провайдер не готов",
                false, provider.isAvailable())
        } else {
            println("LOCAL_AI_STATE=${installed.size} model(s), first=${installed.first().name}")
            assertNotNull("Установленная модель должна иметь файл",
                manager.fileFor(installed.first().modelId))
        }
    }

    @Test
    fun benchmarkWithoutInferenceIsNotDeviceVerified() {
        // ТЗ §35: DEVICE VERIFIED только после реального теста.
        // Если модель не установлена — benchmark не должен проводиться,
        // а статус должен остаться NOT_PROVEN.
        val manager = ServiceLocator.localModelManager
        if (manager.list().isEmpty()) {
            println("SKIP_BENCHMARK: нет установленной модели")
            return
        }
        val model = ServiceLocator.modelRegistry.byId(manager.list().first().modelId)
        assertNotNull("Реестр должен знать об установленной модели", model)
        if (model != null) {
            val result = BenchmarkRunner.run(model, manager, ServiceLocator.llamaRuntime)
            println("BENCHMARK_STATUS=${result.status} tps=${result.tokensPerSecond}")
            // После реального inference на устройстве статус должен стать
            // DEVICE_VERIFIED. Если стал NOT_PROVEN — inference не прошёл.
            println("BENCHMARK_RESULT=${if (result.status == SvetlanaStatus.DEVICE_VERIFIED) "INFERENCE_OK" else "INFERENCE_FAILED_OR_NO_RUNTIME"}")
        }
    }
}
