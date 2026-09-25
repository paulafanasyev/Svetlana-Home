package com.svetlana.home.ai

import com.google.common.truth.Truth.assertThat
import com.svetlana.home.device.DeviceCapabilityManager
import org.junit.Test

class AIModelCompatibilityEngineTest {

    private val registry = AIModelRegistry()
    private val engine = AIModelCompatibilityEngine(device = stubManager())

    private fun caps(
        ramMb: Int, storageMb: Long, cores: Int = 8, gpu: Boolean = true,
        npu: Boolean = true, thermal: String = "none", battery: Int = 80,
        sdk: Int = 34
    ) = DeviceCapabilityManager.Capabilities(
        model = "POCO X3 NFC", androidVersion = "13", sdkInt = sdk,
        abis = listOf("arm64-v8a"), cpuCores = cores,
        ramTotalMb = ramMb, ramAvailableMb = ramMb / 2,
        storageTotalMb = storageMb, storageAvailableMb = storageMb,
        gpu = "3.2", vulkanSupported = true, openGlEsVersion = "3.2",
        nnapiSupported = npu, thermalStatus = thermal,
        batteryLevel = 0, batteryPercent = battery,
        screenInches = 6.67, screenDensityDpi = 395,
        hasCamera = true, hasMicrophone = true, audioSupported = true,
        networkAvailable = true, networkType = "wifi",
        backendSupport = DeviceCapabilityManager.BackendSupport(cpu = true, gpu = gpu, npu = npu, nnapi = npu),
        snapshotAt = 0L
    )

    private fun stubManager(): DeviceCapabilityManager {
        // Тестируем только evaluate(model, caps), который не требует контекста.
        return DeviceCapabilityManager(FakeContext())
    }

    @Test
    fun `small model on 8GB RAM device is likely compatible at least`() {
        val model = registry.byId("qwen2.5-1.5b-instruct-q4")!!
        val report = engine.evaluate(model, caps(ramMb = 8192, storageMb = 65536L))
        assertThat(report.canRunOnDevice).isTrue()
        assertThat(report.level).isNotEqualTo(CompatibilityLevel.INCOMPATIBLE)
    }

    @Test
    fun `7B model on low-RAM device is incompatible`() {
        val model = registry.byId("qwen2.5-7b-instruct-q4")!!
        val report = engine.evaluate(model, caps(ramMb = 2048, storageMb = 8192L))
        assertThat(report.level).isEqualTo(CompatibilityLevel.INCOMPATIBLE)
        assertThat(report.canRunOnDevice).isFalse()
    }

    @Test
    fun `model larger than free storage is incompatible`() {
        val model = registry.byId("gemma2-2b-instruct-q4")!!
        val report = engine.evaluate(model, caps(ramMb = 8192, storageMb = 500L))
        assertThat(report.canRunOnDevice).isFalse()
    }

    @Test
    fun `compatible models sorted and all runnable`() {
        val list = engine.compatibleModels(registry, caps(ramMb = 8192, storageMb = 65536L))
        assertThat(list).isNotEmpty()
        assertThat(list.all { it.canRunOnDevice }).isTrue()
        // Отсортированы по размеру
        val sizes = list.map { it.model.sizeMb }
        assertThat(sizes).isInOrder()
    }

    @Test
    fun `verified level is downgraded to likely before benchmark`() {
        // ТЗ §35: VERIFIED на устройстве ставится только после фактического benchmark.
        val model = registry.byId("llama3.2-1b-instruct-q4")!!
        val report = engine.evaluate(model, caps(ramMb = 12288, storageMb = 131072L, cores = 8))
        assertThat(report.level).isNotEqualTo(CompatibilityLevel.VERIFIED)
    }

    @Test
    fun `best fit returns a runnable model on capable device`() {
        val best = engine.bestFit(registry, caps(ramMb = 12288, storageMb = 131072L, cores = 8))
        assertThat(best).isNotNull()
        assertThat(best!!.canRunOnDevice).isTrue()
    }

    @Test
    fun `expected performance reports tokens per second class`() {
        val model = registry.byId("qwen2.5-3b-instruct-q4")!!
        val report = engine.evaluate(model, caps(ramMb = 6144, storageMb = 32768L))
        assertThat(report.expectedPerf).contains("ток/с")
    }

    /**
     * Минимальный контекст-заглушка: evaluate(model, caps) не обращается к Android API,
     * когда в него передаются готовые capabilities.
     *
     * Наследуемся от ContextWrapper, чтобы не реализовывать все абстрактные методы
     * Context вручную (их количество меняется между версиями Android SDK).
     */
    private class FakeContext : android.content.ContextWrapper(null) {
        override fun getApplicationContext(): android.content.Context = this
        override fun getSystemService(name: String): Any? = null
        override fun checkSelfPermission(permission: String): Int = 0
        override fun getPackageName(): String = "com.svetlana.home"
    }
}
