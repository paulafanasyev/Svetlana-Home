package com.svetlana.home.performance

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.svetlana.home.core.ServiceLocator
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * ТЗ §76: performance на целевом устройстве.
 *
 * Замеряет реальные метрики на устройстве. Значения выводятся в лог
 * (видны в test report) и могут быть внесены в docs/performance.md.
 * Тест не падает от медленных значений — его цель собрать данные,
 * а не валидировать цель. Падение только если метрика недоступна.
 */
@RunWith(AndroidJUnit4::class)
class PerformanceDeviceTest {

    @Before
    fun setUp() {
        ServiceLocator.init(androidx.test.core.app.ApplicationProvider.getApplicationContext())
    }

    @Test
    fun measure_deviceCapabilities_probeLatency() {
        val caps = ServiceLocator.device.current()
        val started = System.currentTimeMillis()

        // Дважды: холодный + тёплый доступ
        ServiceLocator.device.current()
        val warmMs = System.currentTimeMillis() - started

        println("PERF_DEVICE_PROBE_COLD=warm=${warmMs}ms")
        println("PERF_DEVICE: cores=${caps.cpuCores}, ram=${caps.ramTotalMb}MB, " +
            "free=${caps.ramAvailableMb}MB, storage=${caps.storageTotalMb}MB, " +
            "gpu=${caps.gpu}, thermal=${caps.thermalStatus}, battery=${caps.batteryPercent}%")
        assertTrue("Device probe должен работать", caps.cpuCores > 0)
        assertTrue("RAM должна быть определена", caps.ramTotalMb > 0)
    }

    @Test
    fun measure_appRegistry_scanLatency() {
        val started = System.currentTimeMillis()
        ServiceLocator.appRegistry.scan()
        val scanMs = System.currentTimeMillis() - started

        val count = ServiceLocator.appRegistry.apps.value.size
        println("PERF_APP_REGISTRY_SCAN=${scanMs}ms apps=$count")
        assertTrue("Реестр должен найти приложения", count > 0)
    }

    @Test
    fun measure_translator_localLatency() = runBlocking {
        // Локальный словарь — самый быстрый путь
        val started = System.currentTimeMillis()
        val result = ServiceLocator.translator.translateText(
            "привет", com.svetlana.home.translate.TranslateDirection.RU_TO_VI)
        val ms = System.currentTimeMillis() - started

        println("PERF_TRANSLATE_LOCAL=${ms}ms success=${result.success}")
        assertTrue("Локальный перевод должен работать", result.success)
    }

    @Test
    fun measure_avatarDecision_latency() {
        val started = System.currentTimeMillis()
        val decision = ServiceLocator.avatarEngine.decide()
        val ms = System.currentTimeMillis() - started

        println("PERF_AVATAR_DECISION=${ms}ms requested=${decision.requestedLevel.label} " +
            "selected=${decision.selectedLevel.label}")
        assertTrue("Avatar decision должна быть", decision.selectedLevel != null)
    }

    @Test
    fun measure_aiRouter_backendLabelLatency() = runBlocking {
        val started = System.currentTimeMillis()
        val label = ServiceLocator.aiRouter.currentBackendLabel()
        val ms = System.currentTimeMillis() - started

        println("PERF_BACKEND_LABEL=${ms}ms label=$label")
        assertTrue("Backend label должна быть", label.isNotBlank())
    }

    @Test
    fun measure_permissionManager_reportLatency() {
        val started = System.currentTimeMillis()
        ServiceLocator.permissionManager.missing()
        val ms = System.currentTimeMillis() - started

        println("PERF_PERMISSION_CHECK=${ms}ms")
        assertTrue("Проверка разрешений должна быть быстрой (< 2с)", ms < 2000)
    }

    @Test
    fun measure_proofChain_overhead() = runBlocking {
        // Proof chain не должна добавлять существенный overhead
        val started = System.currentTimeMillis()
        ServiceLocator.controlEngine.openApp("настройки")
        val ms = System.currentTimeMillis() - started

        println("PERF_OPEN_APP_PROOF=${ms}ms")
        // Включая waitForPackage — допустимо до 10 секунд
        assertTrue("Открытие настроек с proof chain уложилось в 10с", ms < 10000)
    }
}
